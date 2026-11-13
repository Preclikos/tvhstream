package cz.preclikos.tvhstream.recordings

import cz.preclikos.tvhstream.htsp.HtspMessage

/**
 * A Tvheadend DVR entry as sent over HTSP (dvrEntryAdd / dvrEntryUpdate, see
 * htsp_build_dvrentry in tvheadend src/htsp_server.c). Times are epoch seconds.
 */
data class Recording(
    val id: Int,
    val title: String? = null,
    val subtitle: String? = null,
    val channelName: String? = null,
    val start: Long = 0L,
    val stop: Long = 0L,
    /** "scheduled", "recording", "completed", "missed" or "invalid". */
    val state: String? = null,
    val error: String? = null,
    val dataSize: Long? = null,
) {
    val isRecording: Boolean get() = state == STATE_RECORDING

    val durationSec: Long get() = (stop - start).coerceAtLeast(0L)

    /**
     * Something to play: a finished recording whose file exists (a completed one with
     * stream errors is still playable), or one that is being recorded right now.
     */
    val isPlayable: Boolean
        get() = when (state) {
            STATE_RECORDING -> true
            STATE_COMPLETED -> error != ERROR_FILE_MISSING && (dataSize == null || dataSize > 0)
            else -> false
        }

    /** HTSP fileOpen path for this recording (htsp_method_file_open, "dvr/<id>"). */
    val filePath: String get() = "/dvr/$id"

    companion object {
        const val STATE_RECORDING = "recording"
        const val STATE_COMPLETED = "completed"
        const val ERROR_FILE_MISSING = "File missing"
    }
}

/**
 * Keeps the DVR entries of the current connection. dvrEntryUpdate may carry only a few
 * fields (with HTSP > 25 the server sends stats-only updates), so updates are merged
 * onto what we already know instead of replacing the entry.
 */
class RecordingStore {
    private val entries = LinkedHashMap<Int, Recording>()

    /** Applies a dvrEntryAdd/dvrEntryUpdate message; returns false if it had no id. */
    fun upsert(msg: HtspMessage): Boolean {
        val id = msg.int("id") ?: return false
        val old = entries[id] ?: Recording(id)
        // "error" is dropped by the server once the problem is gone, so it is only kept
        // from a full update (one that carries the state).
        val hasState = msg.str("state") != null
        entries[id] = old.copy(
            title = msg.str("title") ?: old.title,
            subtitle = msg.str("subtitle") ?: old.subtitle,
            channelName = msg.str("channelName") ?: old.channelName,
            start = msg.long("start") ?: old.start,
            stop = msg.long("stop") ?: old.stop,
            state = msg.str("state") ?: old.state,
            error = if (hasState) msg.str("error") else old.error,
            dataSize = msg.long("dataSize") ?: old.dataSize,
        )
        return true
    }

    fun delete(msg: HtspMessage): Boolean = msg.int("id")?.let { entries.remove(it) != null } ?: false

    fun clear() = entries.clear()

    /** Playable recordings, newest first (ties by title for a stable order). */
    fun playable(): List<Recording> =
        entries.values
            .filter { it.isPlayable }
            .sortedWith(compareByDescending<Recording> { it.start }.thenBy { it.title.orEmpty() })
}
