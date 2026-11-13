package cz.preclikos.tvhstream.core

/** Small pure helpers for the player's busy indicator. */
object PlayerInfoFormat {

    /** Spinner delay, so a quick buffering blip doesn't flash it on every zap. */
    const val BUSY_DELAY_TUNING_MS = 500L
    const val BUSY_DELAY_REBUFFER_MS = 1_000L

    fun busyDelayMs(hasBeenReady: Boolean): Long =
        if (hasBeenReady) BUSY_DELAY_REBUFFER_MS else BUSY_DELAY_TUNING_MS
}
