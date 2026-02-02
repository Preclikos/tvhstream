package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Connected(val host: String, val port: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class AppConnectionViewModel(
    private val htsp: HtspService,
    private val repo: TvhRepository,
    private val settings: SettingsStore,
    private val passwords: SecurePasswordStore
) : ViewModel() {

    val channels = repo.channelsUi
    val status = repo.status

    private var autoJob: Job? = null

    init {
        repo.startIfNeeded()

        autoJob = viewModelScope.launch(Dispatchers.IO) {
            settings.serverSettings.collectLatest { s ->
                if (s.host.isBlank()) return@collectLatest

                connectInternal(
                    host = s.host,
                    port = s.port,
                    username = s.username,
                    password = passwords.getPassword()
                )
            }
        }
    }

    fun reconnectNow() {
        viewModelScope.launch {
        }
    }

    fun connectOnceFromUi(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectInternal(host, port, username, password)
        }
    }

    private suspend fun connectInternal(
        host: String,
        port: Int,
        username: String,
        password: String
    ) {
        try {
            repo.setStatus("Connecting to $host:$port")
            htsp.connect(host, port, username, password)

            repo.setStatus("Connected")
            htsp.request("enableAsyncMetadata")

            htsp.enableAsyncMetadataAndWaitInitialSync()

            repo.awaitChannelsReady()

            repo.startEpgSnapshotWorker(batchSize = 5)

        } catch (e: Exception) {
            Timber.e(e, "Auto-connect failed")
            repo.setStatus("Connection failed: ${e.message ?: e}")
        }
    }

    fun nowEvent(channelId: Int, nowSec: Long) = repo.nowEvent(channelId, nowSec)
}