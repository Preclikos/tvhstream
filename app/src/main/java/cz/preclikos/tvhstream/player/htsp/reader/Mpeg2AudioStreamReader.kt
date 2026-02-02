package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.player.htsp.utils.TvhMappings

@OptIn(UnstableApi::class)
internal class Mpeg2AudioStreamReader : PlainStreamReader(C.TRACK_TYPE_AUDIO) {


    override fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        var rate = Format.NO_VALUE
        if (stream.fields.contains("rate")) {
            rate = TvhMappings.sriToRate(stream.int("rate")!!)
        }

        // TVHeadend calls all MPEG Audio MPEG2AUDIO - e.g. it could be either mp2 or mp3 audio. We
        // need to use the new audio_version field (4.1.2498+ only). Default to mp2 as that's most
        // common for DVB.
        var audioVersion = 2

        if (stream.fields.contains("audio_version")) {
            audioVersion = stream.int("audio_version")!!
        }

        val mimeType: String = when (audioVersion) {
            1 -> MimeTypes.AUDIO_MPEG_L1    // MP1 Audio - V.Unlikely these days
            2 -> MimeTypes.AUDIO_MPEG_L2    // MP2 Audio - Pretty common in DVB streams
            3 -> MimeTypes.AUDIO_MPEG       // MP3 Audio - Pretty common in IPTV streams
            else -> throw RuntimeException("Unknown MPEG Audio Version: $audioVersion")
        }

        return Format.Builder()
            .setId(streamIndex.toString())
            .setSampleMimeType(mimeType)
            .setChannelCount(stream.int("channels") ?: Format.NO_VALUE)
            .setSampleRate(rate)
            //.setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
            .setLanguage(stream.str("language") ?: "und")
            .build()
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_AUDIO
}