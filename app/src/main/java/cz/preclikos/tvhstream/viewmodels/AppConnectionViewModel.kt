package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.SubStatus
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.services.StatusService
import cz.preclikos.tvhstream.services.StatusSlot
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class AppConnectionViewModel(
    private val htsp: HtspService,
    private val repo: TvhRepository,
    private val statusService: StatusService,
    private val settings: SettingsStore,
    private val passwords: SecurePasswordStore
) : ViewModel() {
    val status = statusService.headline

    private data class ServerCfg(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    @Volatile
    private var lastCfg: ServerCfg? = null
    private var reconnectJob: Job? = null
    private var autoJob: Job? = null

    init {
        repo.startIfNeeded()

        autoJob = viewModelScope.launch(Dispatchers.IO) {
            settings.serverSettings.collectLatest { s ->
                if (s.host.isBlank()) return@collectLatest

                lastCfg = ServerCfg(
                    host = s.host,
                    port = s.port,
                    username = s.username,
                    password = passwords.getPassword()
                )

                startOrRestartReconnectLoop()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            htsp.events.collectLatest { e ->
                when (e) {
                    is HtspEvent.ConnectionError -> {
                        statusService.set(StatusSlot.CONNECTION, "Disconnected. Reconnecting…")
                        repo.onDisconnected()
                        startOrRestartReconnectLoop()
                    }
                    // pokud je to přímo HtspMessage jako event:
                    is HtspEvent.ServerMessage -> {
                        val msg = e.msg

                        msg.toSubStatusOrNull()?.let { st ->
                            subs[st.id] = st
                            publishSubsStatus()
                            return@collectLatest
                        }

                        msg.subStopIdOrNull()?.let { id ->
                            subs.remove(id)
                            publishSubsStatus()
                            return@collectLatest
                        }
                    }
                }
            }
        }
    }

    private val subs = mutableMapOf<Int, SubStatus>()

    private fun publishSubsStatus() {
        val msg = subs.values.computeUiStatus()
        if (msg == null) statusService.set(StatusSlot.CONNECTION, null)
        else statusService.set(StatusSlot.CONNECTION, msg)
    }


    private fun HtspMessage.toSubStatusOrNull(): SubStatus? {
        val m = method ?: return null
        if (m != "subscriptionStatus" && m != "subscriptionStart") return null

        val id = int("subscriptionId")
            ?: int("id")
            ?: return null

        return SubStatus(
            id = id,
            serviceName = str("serviceName") ?: str("channelName") ?: str("service"),
            state = str("state") ?: str("status"),
            errors = int("errors") ?: int("signalErrors") ?: int("ccErrors"),
            input = str("input") ?: str("adapter") ?: str("tuner"),
            username = str("username"),
            hostname = str("hostname"),
        )
    }

    private fun HtspMessage.subStopIdOrNull(): Int? {
        val m = method ?: return null
        if (m != "subscriptionStop") return null
        return int("subscriptionId") ?: int("id")
    }

    private fun Collection<SubStatus>.computeUiStatus(): String? {
        if (isEmpty()) return null

        val noInput = firstOrNull { s ->
            s.state.equals("No input", ignoreCase = true) || s.input.isNullOrBlank()
        }
        if (noInput != null) {
            val svc = noInput.serviceName ?: "channel"
            return "No free tuner / no input ($svc)"
        }

        val scrambled = firstOrNull { it.state.equals("Scrambled", ignoreCase = true) }
        if (scrambled != null) {
            val svc = scrambled.serviceName ?: "channel"
            return "Channel is scrambled ($svc)"
        }

        val worst = maxByOrNull { it.errors ?: 0 }
        val err = worst?.errors ?: 0
        if (err > 0) {
            val svc = worst?.serviceName ?: "channel"
            return "Signal errors: $err ($svc)"
        }

        return null
    }

    private fun startOrRestartReconnectLoop() {
        reconnectJob?.cancel()

        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val cfg = lastCfg ?: return@launch

                val ok = connectInternal(cfg.host, cfg.port, cfg.username, cfg.password)
                if (ok) return@launch

                statusService.set(StatusSlot.CONNECTION, "Reconnect in 5s…")
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    fun reconnectNow() {
        startOrRestartReconnectLoop()
    }

    fun connectOnceFromUi(host: String, port: Int, username: String, password: String) {
        lastCfg = ServerCfg(host, port, username, password)
        startOrRestartReconnectLoop()
    }

    private suspend fun connectInternal(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Boolean {
        return try {
            statusService.set(StatusSlot.SYNC, null)
            statusService.set(StatusSlot.EPG, null)
            statusService.set(StatusSlot.CONNECTION, "Connecting to $host:$port")

            repo.onNewConnectionStarting()

            htsp.connect(host, port, username, password)
            statusService.set(StatusSlot.CONNECTION, "Connected")

            statusService.set(StatusSlot.SYNC, "Syncing…")
            htsp.enableAsyncMetadataAndWaitInitialSync()

            repo.awaitChannelsReady()

            repo.startEpgWorker()

            true
        } catch (e: Exception) {
            Timber.e(e, "Connect failed")
            statusService.set(StatusSlot.CONNECTION, "Connection failed: ${e.message ?: e}")
            false
        }
    }
}