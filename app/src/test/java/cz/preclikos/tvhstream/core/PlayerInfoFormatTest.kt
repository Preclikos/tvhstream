package cz.preclikos.tvhstream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInfoFormatTest {

    @Test
    fun minutesLeft_roundsUp() {
        assertEquals(45, PlayerInfoFormat.minutesLeft(nowSec = 0, stopSec = 45 * 60))
        assertEquals(1, PlayerInfoFormat.minutesLeft(nowSec = 0, stopSec = 10))
        assertEquals(2, PlayerInfoFormat.minutesLeft(nowSec = 0, stopSec = 61))
    }

    @Test
    fun minutesLeft_neverNegative() {
        assertEquals(0, PlayerInfoFormat.minutesLeft(nowSec = 100, stopSec = 50))
    }

    @Test
    fun busyDelay_isShorterWhileTuning() {
        assertEquals(PlayerInfoFormat.BUSY_DELAY_TUNING_MS, PlayerInfoFormat.busyDelayMs(hasBeenReady = false))
        assertEquals(PlayerInfoFormat.BUSY_DELAY_REBUFFER_MS, PlayerInfoFormat.busyDelayMs(hasBeenReady = true))
    }
}
