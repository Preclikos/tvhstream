package cz.preclikos.tvhstream.player

import cz.preclikos.tvhstream.player.htsp.reader.DvbsubStreamReader
import cz.preclikos.tvhstream.player.htsp.reader.StreamReadersFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class DvbsubStreamReaderTest {
    @Test
    fun factory_mapsDvbsubStreamType() {
        assertTrue(StreamReadersFactory().createStreamReader("DVBSUB") is DvbsubStreamReader)
    }
}
