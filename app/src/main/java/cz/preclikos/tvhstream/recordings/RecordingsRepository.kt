package cz.preclikos.tvhstream.recordings

import cz.preclikos.tvhstream.htsp.ConnectionState
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recordings of the connected server, kept from the HTSP async metadata: after
 * enableAsyncMetadata the server sends a dvrEntryAdd for every entry the user may see
 * (htsp_server.c, "Send all DVR entries") and then dvrEntryAdd/Update/Delete on changes.
 * Created eagerly so it is listening before the first connect.
 */
class RecordingsRepository(
    private val htsp: HtspService,
    ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()
    private val store = RecordingStore()

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())

    /** Playable recordings (finished or in progress), newest first. */
    val recordings: StateFlow<List<Recording>> = _recordings

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            htsp.controlEvents.collect { e ->
                when (e) {
                    is HtspEvent.ServerMessage -> handle(e)
                    is HtspEvent.ConnectionError -> reset()
                }
            }
        }
        // A (re)connect resends every entry; start from scratch so entries deleted
        // while we were offline don't linger.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            htsp.state.collect { if (it is ConnectionState.Connecting) reset() }
        }
    }

    private suspend fun handle(e: HtspEvent.ServerMessage) {
        val msg = e.msg
        mutex.withLock {
            val changed = when (msg.method) {
                "dvrEntryAdd", "dvrEntryUpdate" -> store.upsert(msg)
                "dvrEntryDelete" -> store.delete(msg)
                else -> false
            }
            if (changed) _recordings.value = store.playable()
        }
    }

    private suspend fun reset() {
        mutex.withLock {
            store.clear()
            _recordings.value = emptyList()
        }
    }
}
