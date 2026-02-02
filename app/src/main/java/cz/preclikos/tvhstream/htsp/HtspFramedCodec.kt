package cz.preclikos.tvhstream.htsp
import java.io.IOException

/**
 * Shared wire format between HtspSubscriptionDataSource and HtspSubscriptionExtractor.
 *
 * Stream format:
 *  - HEADER (8 bytes)
 *  - repeated frames: [int32 BE payloadLen][payload bytes]
 *
 * Payload format:
 *  - seq: int32 (or -1)
 *  - method: int32 len + bytes (UTF-8)
 *  - fieldCount: int32
 *  - repeated fields:
 *      - key: int32 len + bytes (UTF-8)
 *      - type: ubyte
 *      - value...
 */
object HtspFramedCodec {

    // Keep this here so Extractor + DataSource share one source of truth.
    val HEADER: ByteArray = byteArrayOf(0, 1, 0, 1, 0, 1, 0, 1)

    // Safety limit for framed payload (payloadLen).
    const val MAX_FRAME_SIZE: Int = 2 * 1024 * 1024

    // ---- Value types ----
    private const val T_NULL: Int = 0
    private const val T_INT: Int = 1
    private const val T_LONG: Int = 2
    private const val T_STRING: Int = 3
    private const val T_BIN: Int = 4
    private const val T_BOOL: Int = 5
    private const val T_MAP: Int = 6
    private const val T_LIST: Int = 7

    // ---- Framing helpers ----

    fun frameMessage(message: HtspMessage): ByteArray {
        val payload = encodePayload(message)
        val out = ByteArray(4 + payload.size)
        putIntBE(out, 0, payload.size)
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    /**
     * Decodes a single payload (without [len] prefix).
     */
    fun decodePayload(payload: ByteArray): HtspMessage {
        val r = FastReader(payload)

        val seq = r.getInt()
        val method = String(r.getBytesWithLen(), Charsets.UTF_8)

        val fieldCount = r.getInt()
        val fields = HashMap<String, Any?>(fieldCount)

        repeat(fieldCount) {
            val key = String(r.getBytesWithLen(), Charsets.UTF_8)
            fields[key] = readValue(r)
        }

        return HtspMessage(
            method = method,
            seq = if (seq >= 0) seq else null,
            fields = fields,
            rawPayload = null
        )
    }

    /**
     * Encodes payload (without [len] prefix).
     */
    fun encodePayload(message: HtspMessage): ByteArray {
        val method = message.method ?: ""
        val methodBytes = method.toByteArray(Charsets.UTF_8)

        // Rough sizing to reduce growth: do real UTF-8 sizing for strings/keys.
        var est = 4 + 4 + methodBytes.size + 4 // seq + len+method + fieldCount
        for ((k, v) in message.fields) {
            val kb = k.toByteArray(Charsets.UTF_8)
            est += 4 + kb.size // key bytes with len
            est += 1 // type
            est += estimateValueSize(v)
        }

        val w = FastWriter(maxOf(128, est))

        w.putInt(message.seq ?: -1)
        w.putBytesWithLen(methodBytes)

        w.putInt(message.fields.size)
        for ((key, value) in message.fields) {
            w.putBytesWithLen(key.toByteArray(Charsets.UTF_8))
            writeValue(w, value)
        }

        return w.toByteArray()
    }

    private fun estimateValueSize(v: Any?): Int {
        return when (v) {
            null -> 0
            is Int -> 4
            is Long -> 8
            is Boolean -> 1
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                4 + b.size
            }
            is ByteArray -> 4 + v.size
            is Map<*, *> -> {
                // Very rough: type already counted outside; here estimate content.
                // 4 count + per-entry (key len+bytes + type + value)
                var s = 4
                for ((k, value) in v) {
                    val ks = (k as? String)?.toByteArray(Charsets.UTF_8) ?: continue
                    s += 4 + ks.size + 1 + estimateValueSize(value)
                }
                s
            }
            is List<*> -> {
                var s = 4
                for (item in v) s += 1 + estimateValueSize(item) // type+value; type counted in writeValue for item
                // NOTE: this double counts type for list items if you also add 1 here.
                // Keep it conservative: we'll return only 4 + sum(value sizes) and let growth handle the rest.
                // (Better safe than wrong micro-optimization.)
                4 + v.sumOf { estimateValueSize(it) }
            }
            else -> {
                val b = v.toString().toByteArray(Charsets.UTF_8)
                4 + b.size
            }
        }
    }

    private fun writeValue(w: FastWriter, value: Any?) {
        when (value) {
            null -> w.putByte(T_NULL)

            is Int -> {
                w.putByte(T_INT); w.putInt(value)
            }

            is Long -> {
                w.putByte(T_LONG); w.putLong(value)
            }

            is String -> {
                w.putByte(T_STRING); w.putBytesWithLen(value.toByteArray(Charsets.UTF_8))
            }

            is ByteArray -> {
                w.putByte(T_BIN); w.putBytesWithLen(value)
            }

            is Boolean -> {
                w.putByte(T_BOOL); w.putByte(if (value) 1 else 0)
            }

            is Map<*, *> -> {
                w.putByte(T_MAP)
                w.putInt(value.size)
                for ((k, v) in value) {
                    val key = k as? String ?: continue
                    w.putBytesWithLen(key.toByteArray(Charsets.UTF_8))
                    writeValue(w, v)
                }
            }

            is List<*> -> {
                w.putByte(T_LIST)
                w.putInt(value.size)
                for (item in value) {
                    writeValue(w, item)
                }
            }

            else -> {
                // fallback: send as string
                w.putByte(T_STRING)
                w.putBytesWithLen(value.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun readValue(r: FastReader): Any? {
        return when (val type = r.getUByte()) {
            T_NULL -> null
            T_INT -> r.getInt()
            T_LONG -> r.getLong()
            T_STRING -> String(r.getBytesWithLen(), Charsets.UTF_8)
            T_BIN -> r.getBytesWithLen()
            T_BOOL -> (r.getUByte() != 0)

            T_MAP -> {
                val n = r.getInt()
                val m = HashMap<String, Any?>(n)
                repeat(n) {
                    val k = String(r.getBytesWithLen(), Charsets.UTF_8)
                    val v = readValue(r)
                    m[k] = v
                }
                m
            }

            T_LIST -> {
                val n = r.getInt()
                val list = ArrayList<Any?>(n)
                repeat(n) { list.add(readValue(r)) }
                list
            }

            else -> throw IOException("Unknown value type=$type")
        }
    }

    // ---- Low-level IO helpers ----

    private fun putIntBE(a: ByteArray, off: Int, v: Int) {
        a[off] = (v ushr 24).toByte()
        a[off + 1] = (v ushr 16).toByte()
        a[off + 2] = (v ushr 8).toByte()
        a[off + 3] = v.toByte()
    }

    private class FastReader(private val a: ByteArray) {
        private var p = 0

        fun getUByte(): Int {
            if (p >= a.size) throw IOException("EOF")
            return a[p++].toInt() and 0xFF
        }

        fun getInt(): Int {
            if (p + 4 > a.size) throw IOException("EOF")
            val b0 = a[p++].toInt() and 0xFF
            val b1 = a[p++].toInt() and 0xFF
            val b2 = a[p++].toInt() and 0xFF
            val b3 = a[p++].toInt() and 0xFF
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun getLong(): Long {
            if (p + 8 > a.size) throw IOException("EOF")
            var v = 0L
            repeat(8) {
                v = (v shl 8) or ((a[p++].toInt() and 0xFF).toLong())
            }
            return v
        }

        fun getBytesWithLen(): ByteArray {
            val len = getInt()
            if (len < 0) throw IOException("Negative length")
            if (p + len > a.size) throw IOException("EOF")
            val out = ByteArray(len)
            System.arraycopy(a, p, out, 0, len)
            p += len
            return out
        }
    }

    private class FastWriter(initialCapacity: Int) {
        private var a = ByteArray(initialCapacity)
        private var p = 0

        fun putByte(v: Int) {
            ensure(1)
            a[p++] = v.toByte()
        }

        fun putInt(v: Int) {
            ensure(4)
            a[p++] = (v ushr 24).toByte()
            a[p++] = (v ushr 16).toByte()
            a[p++] = (v ushr 8).toByte()
            a[p++] = v.toByte()
        }

        fun putLong(v: Long) {
            ensure(8)
            a[p++] = (v ushr 56).toByte()
            a[p++] = (v ushr 48).toByte()
            a[p++] = (v ushr 40).toByte()
            a[p++] = (v ushr 32).toByte()
            a[p++] = (v ushr 24).toByte()
            a[p++] = (v ushr 16).toByte()
            a[p++] = (v ushr 8).toByte()
            a[p++] = v.toByte()
        }

        fun putBytesWithLen(bytes: ByteArray) {
            putInt(bytes.size)
            ensure(bytes.size)
            System.arraycopy(bytes, 0, a, p, bytes.size)
            p += bytes.size
        }

        private fun ensure(n: Int) {
            if (p + n <= a.size) return
            var newCap = a.size
            while (p + n > newCap) newCap *= 2
            a = a.copyOf(newCap)
        }

        fun toByteArray(): ByteArray = a.copyOf(p)
    }
}
