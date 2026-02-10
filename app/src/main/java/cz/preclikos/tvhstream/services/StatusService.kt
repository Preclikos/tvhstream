package cz.preclikos.tvhstream.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class StatusSlot { CONNECTION, SYNC, EPG }

interface StatusService {
    val connection: StateFlow<String?>
    val sync: StateFlow<String?>
    val epg: StateFlow<String?>

    val headline: StateFlow<String>

    fun set(slot: StatusSlot, text: String?)
}

class StatusServiceImpl : StatusService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connection = MutableStateFlow<String?>(null)
    private val _sync = MutableStateFlow<String?>(null)
    private val _epg = MutableStateFlow<String?>(null)

    override val connection: StateFlow<String?> = _connection
    override val sync: StateFlow<String?> = _sync
    override val epg: StateFlow<String?> = _epg

    override val headline: StateFlow<String> =
        combine(_connection, _sync, _epg) { c, s, e ->
            val conn = c?.takeIf { it.isNotBlank() }
            val connIsStrong = conn != null && conn != "Connected"

            when {
                connIsStrong -> conn
                !s.isNullOrBlank() -> s
                !e.isNullOrBlank() -> e
                !conn.isNullOrBlank() -> conn
                else -> ""
            }
        }.stateIn(scope, SharingStarted.Eagerly, "")

    override fun set(slot: StatusSlot, text: String?) {
        when (slot) {
            StatusSlot.CONNECTION -> _connection.value = text
            StatusSlot.SYNC -> _sync.value = text
            StatusSlot.EPG -> _epg.value = text
        }
    }
}