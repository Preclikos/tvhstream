package cz.preclikos.tvhstream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInfoFormatTest {

    @Test
    fun busyDelay_isShorterWhileTuning() {
        assertEquals(PlayerInfoFormat.BUSY_DELAY_TUNING_MS, PlayerInfoFormat.busyDelayMs(hasBeenReady = false))
        assertEquals(PlayerInfoFormat.BUSY_DELAY_REBUFFER_MS, PlayerInfoFormat.busyDelayMs(hasBeenReady = true))
    }
}
