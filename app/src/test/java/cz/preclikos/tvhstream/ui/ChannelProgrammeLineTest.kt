package cz.preclikos.tvhstream.ui

import cz.preclikos.tvhstream.ui.components.channelProgrammeLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelProgrammeLineTest {
    @Test
    fun withStartTime_prefixesIt() {
        assertEquals("20:00 • News", channelProgrammeLine("20:00", "News"))
    }

    @Test
    fun withoutStartTime_isJustTheTitle() {
        assertEquals("No EPG", channelProgrammeLine(null, "No EPG"))
        assertEquals("No EPG", channelProgrammeLine("", "No EPG"))
    }
}
