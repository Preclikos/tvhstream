package cz.preclikos.tvhstream.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePausePolicyTest {

    @Test
    fun notPaused_doesNotRetune() {
        assertFalse(LivePausePolicy.shouldRetuneOnResume(pausedAtMs = null, nowMs = 100_000))
    }

    @Test
    fun shortPause_resumesFromBuffer() {
        assertFalse(LivePausePolicy.shouldRetuneOnResume(pausedAtMs = 100_000, nowMs = 103_000))
    }

    @Test
    fun pauseExactlyAtLimit_resumesFromBuffer() {
        val limit = LivePausePolicy.MAX_RESUME_FROM_BUFFER_MS
        assertFalse(LivePausePolicy.shouldRetuneOnResume(pausedAtMs = 0, nowMs = limit))
    }

    @Test
    fun longPause_retunesToLive() {
        assertTrue(LivePausePolicy.shouldRetuneOnResume(pausedAtMs = 100_000, nowMs = 160_000))
    }
}
