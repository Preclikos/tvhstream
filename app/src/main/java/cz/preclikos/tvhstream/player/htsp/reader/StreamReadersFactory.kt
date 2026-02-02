package cz.preclikos.tvhstream.player.htsp.reader

class StreamReadersFactory {

    fun createStreamReader(streamType: String): StreamReader? {
        when (streamType) {
            // Video Stream Types
            "H264" -> return H264StreamReader()
            "HEVC" -> return H265StreamReader()
            "MPEG2VIDEO" -> return Mpeg2VideoStreamReader()
            // Audio Stream Types*/
            "AAC" -> return AacStreamReader()
            "AC3" -> return Ac3StreamReader()
            "EAC3" -> return Eac3StreamReader()
            "MPEG2AUDIO" -> return Mpeg2AudioStreamReader()/*
            "VORBIS" -> return VorbisStreamReader()
            // Text Stream Types
            "TEXTSUB" -> return TextsubStreamReader()*/
            "DVBSUB" -> return DvbsubStreamReader()
            else -> return null
        }
    }
}