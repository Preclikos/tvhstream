package cz.preclikos.tvhstream.htsp

import timber.log.Timber
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.math.min

object HtspCodec {

    private const val TYPE_MAP: Int = 1
    private const val TYPE_S64: Int = 2
    private const val TYPE_STR: Int = 3
    private const val TYPE_BIN: Int = 4
    private const val TYPE_LIST: Int = 5
    private const val TYPE_DBL: Int = 6
    private const val TYPE_BOOL: Int = 7
    private const val TYPE_UUID: Int = 8

    private const val MAX_MESSAGE_SIZE = 32 * 1024 * 1024
    private const val MAX_FIELD_NAME = 255
    private const val MAX_NESTING_DEPTH = 32

    fun readMessage(input: InputStream): HtspMessage {
        // ---- Root 4B length ----
        val hdr = ByteArray(4)
        readFullySoft(input, hdr, len = 4, what = "rootLen")

        val declaredLen =
            (((hdr[0].toLong() and 0xFF) shl 24) or
                    ((hdr[1].toLong() and 0xFF) shl 16) or
                    ((hdr[2].toLong() and 0xFF) shl 8)  or
                    ( hdr[3].toLong() and 0xFF)) and 0xFFFF_FFFFL

        // ---- THE ONLY FATAL CONDITION (can't safely "just read to end") ----
        if (declaredLen <= 0L || declaredLen > MAX_MESSAGE_SIZE.toLong()) {
            val hex = hdr.joinToString(" ") { "%02x".format(it) }
            Timber.e("HTSP invalid root length=%d hdr=%s (fatal -> reconnect)", declaredLen, hex)
            throw IllegalStateException("Invalid HTSP message length: $declaredLen hdr=$hex")
        }

        val len = declaredLen.toInt()
        val reader = BoundedReader(input, len)

        val fields = LinkedHashMap<String, Any?>()
        decodeMap(reader, fields, depth = 0)

        // Always drain any leftover bytes so next message stays aligned
        reader.drainSoft(what = "messageTailDrain")

        val method = fields["method"] as? String
        val seq = (fields["seq"] as? Number)?.toInt()
        val rawPayload = if (method == "muxpkt") fields["payload"] as? ByteArray else null

        return HtspMessage(
            method = method,
            seq = seq,
            fields = fields,
            rawPayload = rawPayload
        )
    }

    fun writeMessage(output: OutputStream, method: String, fields: Map<String, Any?>) {
        val root = LinkedHashMap<String, Any?>()
        root["method"] = method
        for ((k, v) in fields) root[k] = v

        val body = encodeMapBody(root)
        writeU32BE(output, body.size)
        output.write(body)
    }

    fun isMuxPkt(msg: HtspMessage): Boolean = msg.method == "muxpkt"

    fun tsPayload(msg: HtspMessage): ByteArray? =
        msg.rawPayload ?: (msg.fields["payload"] as? ByteArray)

    // ----------------------------
    // DECODING
    // ----------------------------

    private fun decodeMap(r: BoundedReader, out: MutableMap<String, Any?>, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) throw IllegalStateException("HTSP nesting too deep: $depth")
        while (r.remaining > 0) {
            val (name, value) = decodeField(r, depth)
            if (name != null) out[name] = value
        }
    }

    private fun decodeList(r: BoundedReader, out: MutableList<Any?>, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) throw IllegalStateException("HTSP nesting too deep: $depth")
        while (r.remaining > 0) {
            val (_, value) = decodeField(r, depth)
            out.add(value)
        }
    }

    private fun decodeField(r: BoundedReader, depth: Int): Pair<String?, Any?> {
        val type = r.readU8Soft()
        val nameLen = r.readU8Soft()
        if (nameLen > MAX_FIELD_NAME) throw IllegalStateException("Field name too long: $nameLen")

        val dataLenU = r.readU32BESoft()
        if (dataLenU > r.remaining.toLong()) throw IllegalStateException("Truncated HTSP field data")
        val dataLen = dataLenU.toInt()

        val name = if (nameLen > 0) {
            val nb = r.readExactlySoft(nameLen, what = "fieldName")
            String(nb, StandardCharsets.UTF_8)
        } else null

        val data = r.slice(dataLen)

        val value: Any? = when (type) {
            TYPE_MAP -> LinkedHashMap<String, Any?>().also { decodeMap(data, it, depth + 1) }
            TYPE_LIST -> ArrayList<Any?>().also { decodeList(data, it, depth + 1) }
            TYPE_S64 -> readS64VarLenLE(data)
            TYPE_STR -> String(data.readExactlySoft(dataLen, what = "str"), StandardCharsets.UTF_8)
            TYPE_BIN -> data.readExactlySoft(dataLen, what = "bin")
            TYPE_DBL -> readDoubleLE(data, dataLen)
            TYPE_BOOL -> readBool(data, dataLen)
            TYPE_UUID -> data.readExactlySoft(dataLen, what = "uuid")
            else -> data.readExactlySoft(dataLen, what = "unknown")
        }

        // ensure slice fully consumed (keeps parent aligned)
        data.drainSoft(what = "fieldDrain")

        return name to value
    }

    private fun readS64VarLenLE(r: BoundedReader): Long {
        val len = r.remaining
        if (len == 0) return 0L
        val n = min(len, 8)
        var v = 0L
        for (i in 0 until n) {
            v = v or ((r.readU8Soft().toLong() and 0xFFL) shl (8 * i))
        }
        // consume any leftover bytes in this slice (if any)
        r.drainSoft(what = "s64TailDrain")
        return v
    }

    private fun readDoubleLE(r: BoundedReader, len: Int): Double {
        if (len != 8) {
            r.drainSoft(what = "dblLenMismatchDrain")
            return 0.0
        }
        var bits = 0L
        for (i in 0 until 8) {
            bits = bits or ((r.readU8Soft().toLong() and 0xFFL) shl (8 * i))
        }
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readBool(r: BoundedReader, len: Int): Boolean {
        if (len <= 0) return false
        val v = r.readU8Soft() != 0
        r.drainSoft(what = "boolTailDrain")
        return v
    }

    // ----------------------------
    // Root prefix write
    // ----------------------------

    private fun writeU32BE(output: OutputStream, v: Int) {
        output.write((v ushr 24) and 0xFF)
        output.write((v ushr 16) and 0xFF)
        output.write((v ushr 8) and 0xFF)
        output.write(v and 0xFF)
    }

    // ----------------------------
    // SOFT read helpers (never throw on SO_TIMEOUT; only log + continue)
    // ----------------------------

    private fun readFullySoft(
        input: InputStream,
        buf: ByteArray,
        off: Int = 0,
        len: Int = buf.size,
        what: String
    ) {
        var readTotal = 0
        var timeouts = 0
        while (readTotal < len) {
            try {
                val r = input.read(buf, off + readTotal, len - readTotal)
                if (r < 0) throw EOFException("EOF while reading $what ($len bytes, readTotal=$readTotal)")
                readTotal += r
            } catch (e: SocketTimeoutException) {
                timeouts++
                if (timeouts == 1 || timeouts % 50 == 0) {
                    Timber.w(e, "SO_TIMEOUT during $what read len=$len after readTotal=$readTotal (continuing)")
                }
                // keep looping until we read all bytes or EOF
            }
        }
    }

    // ----------------------------
    // BoundedReader (soft timeouts, always consume exact bytes)
    // ----------------------------

    private class BoundedReader(
        private val input: InputStream,
        initialLimit: Int,
        private val parent: BoundedReader? = null
    ) {
        var remaining: Int = initialLimit
            private set

        fun readU8Soft(): Int {
            if (remaining <= 0) throw EOFException("EOF in bounded reader")
            var timeouts = 0
            while (true) {
                try {
                    val r = input.read()
                    if (r < 0) throw EOFException("EOF in bounded reader")
                    remaining -= 1
                    parent?.consumeFromChild(1)
                    return r and 0xFF
                } catch (e: SocketTimeoutException) {
                    timeouts++
                    if (timeouts == 1 || timeouts % 200 == 0) {
                        Timber.w(e, "SO_TIMEOUT during readU8 remaining=$remaining (continuing)")
                    }
                }
            }
        }

        fun readU32BESoft(): Long {
            val b0 = readU8Soft().toLong()
            val b1 = readU8Soft().toLong()
            val b2 = readU8Soft().toLong()
            val b3 = readU8Soft().toLong()
            return (((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3) and 0xFFFF_FFFFL)
        }

        fun readExactlySoft(n: Int, what: String): ByteArray {
            if (n < 0 || n > remaining) throw EOFException("Need $n bytes, remaining=$remaining ($what)")
            val buf = ByteArray(n)

            var off = 0
            var timeouts = 0
            while (off < n) {
                try {
                    val r = input.read(buf, off, n - off)
                    if (r < 0) throw EOFException("EOF while reading $what ($n bytes, off=$off)")
                    off += r
                    remaining -= r
                    parent?.consumeFromChild(r)
                } catch (e: SocketTimeoutException) {
                    timeouts++
                    if (timeouts == 1 || timeouts % 50 == 0) {
                        Timber.w(e, "SO_TIMEOUT during readExactly($what) n=$n off=$off remaining=$remaining (continuing)")
                    }
                }
            }
            return buf
        }

        fun slice(n: Int): BoundedReader {
            if (n < 0 || n > remaining) throw EOFException("Slice $n bytes, remaining=$remaining")
            return BoundedReader(input, n, parent = this)
        }

        fun drainSoft(what: String) {
            if (remaining <= 0) return
            val tmp = ByteArray(8192)
            var timeouts = 0
            while (remaining > 0) {
                val toRead = min(remaining, tmp.size)
                try {
                    val r = input.read(tmp, 0, toRead)
                    if (r < 0) throw EOFException("EOF while draining $what (remaining=$remaining)")
                    remaining -= r
                    parent?.consumeFromChild(r)
                } catch (e: SocketTimeoutException) {
                    timeouts++
                    if (timeouts == 1 || timeouts % 50 == 0) {
                        Timber.w(e, "SO_TIMEOUT during drain($what) remaining=$remaining (continuing)")
                    }
                }
            }
        }

        private fun consumeFromChild(n: Int) {
            remaining -= n
            if (remaining < 0) throw IllegalStateException("Child over-consumed parent bounded reader")
            parent?.consumeFromChild(n)
        }
    }

    // ----------------------------
    // Encoding (unchanged)
    // ----------------------------

    private fun encodeMapBody(map: Map<String, Any?>): ByteArray {
        val out = ByteArrayBuilder()
        for ((name, value) in map) {
            if (value == null) continue
            out.append(encodeField(name, value))
        }
        return out.toByteArray()
    }

    private fun encodeListBody(list: List<Any?>): ByteArray {
        val out = ByteArrayBuilder()
        for (value in list) {
            if (value == null) continue
            out.append(encodeField(null, value))
        }
        return out.toByteArray()
    }

    private fun encodeField(name: String?, value: Any): ByteArray {
        val nameBytes = name?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val nameLen = nameBytes.size
        if (nameLen > 255) error("Field name too long: $nameLen")

        val (typeId, dataBytes) = encodeValue(value)

        val out = ByteArrayBuilder()
        out.appendByte(typeId)
        out.appendByte(nameLen)
        out.appendU32BE(dataBytes.size)
        out.append(nameBytes)
        out.append(dataBytes)
        return out.toByteArray()
    }

    private fun encodeValue(value: Any): Pair<Int, ByteArray> {
        return when (value) {
            is String -> TYPE_STR to value.toByteArray(StandardCharsets.UTF_8)
            is ByteArray -> TYPE_BIN to value
            is Boolean -> TYPE_BOOL to byteArrayOf(if (value) 1 else 0)
            is Double -> TYPE_DBL to writeDoubleLE(value)
            is Float -> TYPE_DBL to writeDoubleLE(value.toDouble())
            is Int -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Long -> TYPE_S64 to writeS64VarLen(value)
            is Short -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Byte -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Number -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Map<*, *> -> {
                val m = value.entries.associate { (k, v) ->
                    (k?.toString() ?: error("Map key is null")) to v
                }
                TYPE_MAP to encodeMapBody(m)
            }
            is List<*> -> TYPE_LIST to encodeListBody(value)
            else -> error("Unsupported HTSP field type: ${value::class.java.name}")
        }
    }

    private fun writeS64VarLen(v: Long): ByteArray {
        if (v < 0) return writeLongLE(v)
        if (v == 0L) return ByteArray(0)

        var tmp = v
        val bytes = ByteArray(8)
        var len = 0
        while (tmp != 0L) {
            bytes[len] = (tmp and 0xFF).toByte()
            tmp = tmp ushr 8
            len++
        }
        return bytes.copyOf(len)
    }

    private fun writeLongLE(v: Long): ByteArray {
        val b = ByteArray(8)
        var x = v
        for (i in 0 until 8) {
            b[i] = (x and 0xFF).toByte()
            x = x shr 8
        }
        return b
    }

    private fun writeDoubleLE(v: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        return writeLongLE(bits)
    }

    private class ByteArrayBuilder(initial: Int = 256) {
        private var a = ByteArray(initial)
        private var n = 0

        fun append(bytes: ByteArray) {
            ensure(n + bytes.size)
            System.arraycopy(bytes, 0, a, n, bytes.size)
            n += bytes.size
        }

        fun appendByte(v: Int) {
            ensure(n + 1)
            a[n++] = (v and 0xFF).toByte()
        }

        fun appendU32BE(v: Int) {
            ensure(n + 4)
            a[n++] = ((v ushr 24) and 0xFF).toByte()
            a[n++] = ((v ushr 16) and 0xFF).toByte()
            a[n++] = ((v ushr 8) and 0xFF).toByte()
            a[n++] = (v and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = a.copyOf(n)

        private fun ensure(cap: Int) {
            if (cap <= a.size) return
            var newSize = a.size
            while (newSize < cap) newSize *= 2
            a = a.copyOf(newSize)
        }
    }
}
