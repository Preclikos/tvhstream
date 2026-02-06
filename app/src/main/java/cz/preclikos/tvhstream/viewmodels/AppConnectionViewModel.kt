package cz.preclikos.tvhstream.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.SubscriptionStatus
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
    private val context: Context,
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
            htsp.controlEvents.collectLatest { e ->
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

    private val subs = mutableMapOf<Int, SubscriptionStatus>()

    private fun publishSubsStatus() {
        val msg = subs.values.computeUiStatus(context)
        if (msg == null) statusService.set(StatusSlot.CONNECTION, null)
        else statusService.set(StatusSlot.CONNECTION, msg)
    }


    private fun HtspMessage.toSubStatusOrNull(): SubscriptionStatus? {
        val m = method ?: return null
        if (m != "subscriptionStatus" && m != "subscriptionStart") return null

        val id = int("subscriptionId")
            ?: int("id")
            ?: return null

        return SubscriptionStatus(
            id = id,
            state = str("state") ?: str("status"),
            subscriptionError = str("subscriptionError") ?: str("error")
        )
    }

    private fun HtspMessage.subStopIdOrNull(): Int? {
        val m = method ?: return null
        if (m != "subscriptionStop") return null
        return int("subscriptionId") ?: int("id")
    }

    private fun Collection<SubscriptionStatus>.computeUiStatus(ctx: Context): String? {
        if (isEmpty()) return null

        fun norm(v: String?): String =
            v?.lowercase()?.replace(" ", "") ?: ""

        for (s in this) {
            val code = norm(s.subscriptionError ?: s.state)

            when {
                "invalidtarget" in code ->
                    return ctx.getString(R.string.tvh_target_invalid)

                "nofreeadapter" in code ->
                    return ctx.getString(R.string.tvh_no_free_adapter)

                "muxnotenabled" in code ->
                    return ctx.getString(R.string.tvh_mux_not_enabled)

                "tuningfailed" in code ->
                    return ctx.getString(R.string.tvh_tuning_failed)

                "badsignal" in code ->
                    return ctx.getString(R.string.tvh_bad_signal)

                "scrambled" in code ->
                    return ctx.getString(R.string.tvh_scrambled)

                "subscriptionoverridden" in code ->
                    return ctx.getString(R.string.tvh_subscription_overridden)

                // fallback starší texty
                "noinput" in code ->
                    return ctx.getString(R.string.tvh_no_input)
            }
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