package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.player.htsp.utils.TvhMappings

@OptIn(UnstableApi::class)
internal class Ac3StreamReader : PlainStreamReader(C.TRACK_TYPE_AUDIO) {
    override fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        var rate = Format.NO_VALUE
        if (stream.fields.contains("rate")) {
            rate = TvhMappings.sriToRate(stream.int("rate") ?: 0)
        }

        return Format.Builder()
            .setId(streamIndex.toString())
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(stream.int("channels") ?: Format.NO_VALUE)
            .setSampleRate(rate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
            .setLanguage(stream.str("language") ?: "und")
            .build()
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_AUDIO
}