package cz.preclikos.tvhstream.core

/**
 * Live TV has no server-side timeshift here, so a pause only freezes the picture while
 * the data source keeps buffering. A short pause can simply continue from the buffer;
 * after a longer one the buffer has overflowed, so resuming must re-tune to live.
 */
object LivePausePolicy {
    /** Roughly what the 10 MB ring buffer holds for an HD stream. */
    const val MAX_RESUME_FROM_BUFFER_MS = 5_000L

    fun shouldRetuneOnResume(pausedAtMs: Long?, nowMs: Long): Boolean {
        if (pausedAtMs == null) return false
        return nowMs - pausedAtMs > MAX_RESUME_FROM_BUFFER_MS
    }
}
