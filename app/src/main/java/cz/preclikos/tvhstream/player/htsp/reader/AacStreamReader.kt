package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.AacUtil.buildAacLcAudioSpecificConfig
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.player.htsp.utils.TvhMappings

@OptIn(UnstableApi::class)
internal class AacStreamReader : StreamReader {

    private var mTrackOutput: TrackOutput? = null

    override fun createTracks(stream: HtspMessage, output: ExtractorOutput) {
        val streamIndex = stream.int("index")!!
        mTrackOutput = output.track(streamIndex, C.TRACK_TYPE_AUDIO)
        mTrackOutput!!.format(buildFormat(streamIndex, stream))
    }

    override fun consume(message: HtspMessage) {
        val pts = message.long("pts")
        val payload = message.bin("payload")
        val pba = ParsableByteArray(payload!!)

        val skipLength: Int = if (hasCrc(payload[1])) {
            // Have a CRC
            ADTS_HEADER_SIZE + ADTS_CRC_SIZE
        } else {
            // No CRC
            ADTS_HEADER_SIZE
        }

        pba.skipBytes(skipLength)

        val aacFrameLength = payload.size - skipLength

        // TODO: Set Buffer Flag key frame based on frametype
        // frametype   u32   required   Type of frame as ASCII value: 'I', 'P', 'B'
        mTrackOutput!!.sampleData(pba, aacFrameLength)
        mTrackOutput!!.sampleMetadata(pts!!, C.BUFFER_FLAG_KEY_FRAME, aacFrameLength, 0, null)
    }

    private fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        val initializationData: List<ByteArray>

        var rate = Format.NO_VALUE
        if (stream.fields.contains("rate")) {
            rate = TvhMappings.sriToRate(stream.int("rate")!!)
        }

        val channels = stream.int("channels") ?: Format.NO_VALUE

        initializationData = if (stream.fields.contains("meta")) {
            listOf(stream.bin("meta")!!)
        } else {
            listOf(buildAacLcAudioSpecificConfig(rate, channels))
        }

        return Format.Builder()
            .setId(streamIndex.toString())
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(channels)
            .setSampleRate(rate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
            .setLanguage(stream.str("language") ?: "und")
            .build()
    }

    private fun hasCrc(b: Byte): Boolean {
        val data = b.toInt() and 0xFF
        return (data and 0x1) == 0
    }

    companion object {

        private const val ADTS_HEADER_SIZE = 7
        private const val ADTS_CRC_SIZE = 2
    }
}