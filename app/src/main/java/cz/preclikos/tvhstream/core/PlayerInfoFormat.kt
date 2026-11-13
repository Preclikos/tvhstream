package cz.preclikos.tvhstream.core

/** Small pure helpers for the player's info banner and busy indicator. */
object PlayerInfoFormat {

    /** Whole minutes left until [stopSec], rounded up so "0 min" only shows at the end. */
    fun minutesLeft(nowSec: Long, stopSec: Long): Long {
        val left = (stopSec - nowSec).coerceAtLeast(0L)
        return (left + 59) / 60
    }

    /** Spinner delay, so a quick buffering blip doesn't flash it on every zap. */
    const val BUSY_DELAY_TUNING_MS = 500L
    const val BUSY_DELAY_REBUFFER_MS = 1_000L

    fun busyDelayMs(hasBeenReady: Boolean): Long =
        if (hasBeenReady) BUSY_DELAY_REBUFFER_MS else BUSY_DELAY_TUNING_MS
}
