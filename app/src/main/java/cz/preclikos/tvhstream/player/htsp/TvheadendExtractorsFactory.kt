package cz.preclikos.tvhstream.player.htsp

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

@OptIn(UnstableApi::class)
internal class TvheadendExtractorsFactory : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        return arrayOf(
            HtspSubscriptionExtractor(),
            /*MatroskaExtractor(),
            FragmentedMp4Extractor(),
            Mp4Extractor(),
            Mp3Extractor(),
            AdtsExtractor(),
            Ac3Extractor(),
            TsExtractor(),
            FlvExtractor(),
            OggExtractor(),
            PsExtractor(),
            WavExtractor()*/
        )
    }
}