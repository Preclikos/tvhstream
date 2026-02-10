package cz.preclikos.tvhstream.services

import android.content.Context
import androidx.annotation.StringRes
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
    val connection: StateFlow<UiText?>
    val sync: StateFlow<UiText?>
    val epg: StateFlow<UiText?>

    val headline: StateFlow<UiText?>

    fun set(slot: StatusSlot, msg: UiText?)
}

class StatusServiceImpl : StatusService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connection = MutableStateFlow<UiText?>(null)
    private val _sync = MutableStateFlow<UiText?>(null)
    private val _epg = MutableStateFlow<UiText?>(null)

    override val connection: StateFlow<UiText?> = _connection
    override val sync: StateFlow<UiText?> = _sync
    override val epg: StateFlow<UiText?> = _epg

    private fun UiText.isWeakConnected(): Boolean = when (this) {
        is UiText.Plain -> value.equals("Connected", ignoreCase = true)
        is UiText.Res -> false // bez Contextu bezpečně nepoznáš; můžeš porovnat s konkrétním R.string, pokud ho máš
    }

    override val headline: StateFlow<UiText?> =
        combine(_connection, _sync, _epg) { c, s, e ->
            val connStrong = c != null && !c.isWeakConnected()

            when {
                connStrong -> c
                s != null -> s
                e != null -> e
                c != null -> c
                else -> null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun set(slot: StatusSlot, msg: UiText?) {
        when (slot) {
            StatusSlot.CONNECTION -> _connection.value = msg
            StatusSlot.SYNC -> _sync.value = msg
            StatusSlot.EPG -> _epg.value = msg
        }
    }
}

sealed class UiText {
    data class Plain(val value: String) : UiText()
    data class Res(@param:StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText()

    fun resolve(ctx: Context): String = when (this) {
        is Plain -> value
        is Res -> if (args.isEmpty()) ctx.getString(resId) else ctx.getString(
            resId,
            *args.toTypedArray()
        )
    }
}