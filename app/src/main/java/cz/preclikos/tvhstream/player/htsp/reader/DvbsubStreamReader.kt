package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import cz.preclikos.tvhstream.htsp.HtspMessage

@OptIn(UnstableApi::class)
internal class DvbsubStreamReader : PlainStreamReader(C.TRACK_TYPE_TEXT) {

    override fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        val compositionId = stream.int("composition_id")!!
        val ancillaryId = stream.int("ancillary_id")!!
        val initializationData = listOf(
            byteArrayOf(
                (compositionId shr 8 and 0xFF).toByte(),
                (compositionId and 0xFF).toByte(),
                (ancillaryId shr 8 and 0xFF).toByte(),
                (ancillaryId and 0xFF).toByte()
            )
        )

        return Format.Builder()
            .setId(streamIndex.toString())
            .setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setInitializationData(initializationData)
            .setLanguage(stream.str("language") ?: "und")
            .build()
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_TEXT
}