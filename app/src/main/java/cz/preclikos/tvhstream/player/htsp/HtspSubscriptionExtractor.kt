package cz.preclikos.tvhstream.player.htsp

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import cz.preclikos.tvhstream.htsp.HtspFramedCodec
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.player.htsp.reader.StreamReader
import cz.preclikos.tvhstream.player.htsp.reader.StreamReadersFactory
import timber.log.Timber
import java.io.IOException

@UnstableApi
internal class HtspSubscriptionExtractor : Extractor {

    private lateinit var mOutput: ExtractorOutput
    private val mStreamReaders = SparseArray<StreamReader>()

    // raw read chunk
    private val mRawBytes = ByteArray(1024 * 1024)

    // framed stream state
    private val acc = ByteAccumulator(256 * 1024)
    private var headerConsumed = false

    private class HtspSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val point = SeekPoint(0L, 0L)
            return SeekMap.SeekPoints(point)
        }
    }

    // Extractor Methods
    @Throws(IOException::class, InterruptedException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val scratch = ParsableByteArray(HtspFramedCodec.HEADER.size)
        input.peekFully(scratch.data, 0, HtspFramedCodec.HEADER.size)
        return scratch.data.contentEquals(HtspFramedCodec.HEADER)
    }

    override fun init(output: ExtractorOutput) {
        mOutput = output
        mOutput.seekMap(HtspSeekMap())
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val bytesRead = input.read(mRawBytes, 0, mRawBytes.size)
        if (bytesRead == -1) {
            Timber.d("End of input")
            return Extractor.RESULT_END_OF_INPUT
        }
        if (bytesRead == 0) return Extractor.RESULT_CONTINUE

        acc.append(mRawBytes, 0, bytesRead)

        // 1) Consume HEADER once
        if (!headerConsumed) {
            if (acc.size < HtspFramedCodec.HEADER.size) return Extractor.RESULT_CONTINUE
            val hdr = acc.peekBytes(HtspFramedCodec.HEADER.size)
            if (!hdr.contentEquals(HtspFramedCodec.HEADER)) {
                Timber.e("Bad header: stream is not HTSP subscription format")
                return Extractor.RESULT_END_OF_INPUT
            }
            acc.skip(HtspFramedCodec.HEADER.size)
            headerConsumed = true
        }

        // 2) Parse frames: [int32 payloadLen][payload bytes]
        while (true) {
            if (acc.size < 4) break

            val payloadLen = acc.peekIntBE()
            if (payloadLen <= 0 || payloadLen > HtspFramedCodec.MAX_FRAME_SIZE) {
                Timber.e("Invalid frame length=%d (accSize=%d)", payloadLen, acc.size)
                return Extractor.RESULT_END_OF_INPUT
            }

            if (acc.size < 4 + payloadLen) break

            acc.skip(4)
            val payload = acc.readBytes(payloadLen)

            try {
                val msg = HtspFramedCodec.decodePayload(payload)
                handleMessage(msg)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to decode framed message (len=%d)", payloadLen)
                // Tady můžeš být tolerantní a pokračovat, ale při rozhozeném streamu je lepší failnout.
                // return Extractor.RESULT_END_OF_INPUT
            }
        }

        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        Timber.d("Seeking HTSP Extractor to position:$position and timeUs:$timeUs")
        // Při seeku typicky zahodíme rozpracovaný frame
        acc.clear()
        headerConsumed = false
        mStreamReaders.clear()
    }

    override fun release() {
        Timber.i("Releasing HTSP Extractor")
        mStreamReaders.clear()
        acc.clear()
        headerConsumed = false
    }

    // Internal Methods
    private fun handleMessage(message: HtspMessage) {
        when (message.method) {
            "subscriptionStart" -> handleSubscriptionStart(message)
            "muxpkt" -> handleMuxpkt(message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToHtspMessage(map: Any): HtspMessage {
        return HtspMessage(
            method = null,
            seq = null,
            fields = map as Map<String, Any?>,
            rawPayload = null
        )
    }

    private fun handleSubscriptionStart(message: HtspMessage) {
        Timber.d("Handling Subscription Start")
        val streamReadersFactory = StreamReadersFactory()

        for (obj in message.list("streams") ?: emptyList()) {
            val stream = mapToHtspMessage(obj!!)
            val streamIndex = stream.int("index")
            val streamType = stream.str("type")

            val streamReader = streamReadersFactory.createStreamReader(streamType!!)
            if (streamReader != null) {
                Timber.d("Creating StreamReader for $streamType stream at index $streamIndex")
                streamReader.createTracks(stream, mOutput)
                mStreamReaders.put(streamIndex!!, streamReader)
            } else {
                Timber.d("Discarding stream at index $streamIndex, no suitable StreamReader")
            }
        }

        Timber.d("All streams have now been handled")
        mOutput.endTracks()
    }

    private fun handleMuxpkt(message: HtspMessage) {
        val streamIdx = message.int("stream") ?: return
        val streamReader = mStreamReaders.get(streamIdx) ?: return
        streamReader.consume(message)
    }

    // ---------- Helpers ----------

    private class ByteAccumulator(initialCapacity: Int) {
        private var a = ByteArray(initialCapacity)
        private var r = 0 // read index
        private var w = 0 // write index

        val size: Int get() = w - r

        fun clear() {
            r = 0
            w = 0
        }

        fun append(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, off, a, w, len)
            w += len
        }

        fun peekBytes(len: Int): ByteArray {
            val out = ByteArray(len)
            System.arraycopy(a, r, out, 0, len)
            return out
        }

        fun peekIntBE(): Int {
            val b0 = a[r].toInt() and 0xFF
            val b1 = a[r + 1].toInt() and 0xFF
            val b2 = a[r + 2].toInt() and 0xFF
            val b3 = a[r + 3].toInt() and 0xFF
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun skip(n: Int) {
            r += n
            if (r == w) {
                r = 0
                w = 0
            } else if (r > a.size / 2) {
                // compact
                val remaining = w - r
                System.arraycopy(a, r, a, 0, remaining)
                r = 0
                w = remaining
            }
        }

        fun readBytes(len: Int): ByteArray {
            val out = ByteArray(len)
            System.arraycopy(a, r, out, 0, len)
            skip(len)
            return out
        }

        private fun ensure(extra: Int) {
            // Try to compact before growing.
            if (extra <= a.size - w && r == 0) return
            if (extra <= a.size - w) return

            // compact if it helps
            if (r > 0) {
                val remaining = w - r
                System.arraycopy(a, r, a, 0, remaining)
                r = 0
                w = remaining
                if (extra <= a.size - w) return
            }

            // grow
            val need = w + extra
            var newCap = a.size
            while (newCap < need) newCap *= 2
            a = a.copyOf(newCap)
        }
    }
}
