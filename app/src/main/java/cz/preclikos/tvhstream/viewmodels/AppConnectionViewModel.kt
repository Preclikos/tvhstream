package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspService
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

    val channels = repo.channelsUi
    val status = statusService.headline
    private data class ServerCfg(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    @Volatile private var lastCfg: ServerCfg? = null
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
                if (e is HtspEvent.ConnectionError) {
                    statusService.set(StatusSlot.CONNECTION, "Disconnected. Reconnecting…")
                    startOrRestartReconnectLoop()
                }
            }
        }
    }

    private fun startOrRestartReconnectLoop() {
        reconnectJob?.cancel()

        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val cfg = lastCfg ?: return@launch

                // zkus connect
                val ok = connectInternal(cfg.host, cfg.port, cfg.username, cfg.password)
                if (ok) return@launch

                // nevyšlo -> počkej 5s a zkus znovu
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

            // reset lokálního stavu před connectem/reconnectem
            repo.onNewConnectionStarting()

            htsp.connect(host, port, username, password)
            statusService.set(StatusSlot.CONNECTION, "Connected")

            statusService.set(StatusSlot.SYNC, "Syncing…")
            htsp.enableAsyncMetadataAndWaitInitialSync()

            // repo si při initialSyncCompleted odemkne barrier
            repo.awaitChannelsReady()

            repo.startEpgSnapshotWorker(batchSize = 5)

            true
        } catch (e: Exception) {
            Timber.e(e, "Connect failed")
            statusService.set(StatusSlot.CONNECTION, "Connection failed: ${e.message ?: e}")
            false
        }
    }

    fun nowEvent(channelId: Int, nowSec: Long) = repo.nowEvent(channelId, nowSec)
}