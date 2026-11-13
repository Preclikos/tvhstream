package cz.preclikos.tvhstream.viewmodels

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import cz.preclikos.tvhstream.core.UntrustedCertificateException
import cz.preclikos.tvhstream.core.CertificateChangedException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.core.ConnectionPolicy
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.SubscriptionStatus
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.services.StatusService
import cz.preclikos.tvhstream.services.StatusSlot
import cz.preclikos.tvhstream.services.UiText
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.ServerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class AppConnectionViewModel(
    private val htsp: HtspService,
    private val repo: TvhRepository,
    private val statusService: StatusService,
    private val settings: ServerSettingsStore,
    private val passwords: SecurePasswordStore,
) : ViewModel() {

    val status = statusService.headline

    private data class ServerCfg(
        val host: String,
        val htspPort: Int,
        val username: String,
        val password: String,
        val tls: Boolean = false,
        val tlsFingerprint: String = "",
    )

    /**
     * Shown when the TLS server certificate needs the user's decision: a self-signed
     * certificate seen for the first time ([previous] == null) or a pinned one that
     * changed ([previous] = the remembered fingerprint).
     */
    data class CertificatePrompt(val host: String, val fingerprint: String, val previous: String?)

    private val _certificatePrompt = MutableStateFlow<CertificatePrompt?>(null)
    val certificatePrompt: StateFlow<CertificatePrompt?> = _certificatePrompt

    @Volatile
    private var lastCfg: ServerCfg? = null
    private var reconnectJob: Job? = null
    private var autoJob: Job? = null

    private val subs = mutableMapOf<Int, SubscriptionStatus>()

    init {
        repo.startIfNeeded()

        autoJob = viewModelScope.launch(Dispatchers.IO) {
            combine(
                settings.serverSettings,
                passwords.passwordFlow
            ) { s, pwd -> s to pwd }
                // Auto-connect as soon as host + port are configured. Username/password
                // are optional: an unauthenticated TVHeadend is connected with empty
                // credentials (HtspService.connect skips the auth step when either is blank).
                .filter { (s, _) -> ConnectionPolicy.isAutoConnectReady(s.host, s.htspPort) }
                .map { (s, pwd) ->
                    ServerCfg(
                        host = s.host,
                        htspPort = s.htspPort,
                        username = s.username,
                        password = pwd,
                        tls = s.tls,
                        tlsFingerprint = s.tlsFingerprint,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { cfg ->
                    lastCfg = cfg
                    startOrRestartReconnectLoop()
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            htsp.controlEvents.collectLatest { e ->
                when (e) {
                    is HtspEvent.ConnectionError -> {
                        statusService.set(
                            StatusSlot.CONNECTION,
                            UiText.Plain("Disconnected. Reconnecting…")
                        )
                        repo.onDisconnected()
                        startOrRestartReconnectLoop()
                    }

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

    override fun onCleared() {
        // Activity is finishing (Back from home). The HTSP connection and EPG worker are
        // app-scoped singletons, so shut them down explicitly instead of killing the process.
        repo.stopEpgWorker()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try {
                htsp.disconnect()
            } catch (t: Throwable) {
                Timber.w(t, "Disconnect on exit failed")
            }
        }
        super.onCleared()
    }

    private fun publishSubsStatus() {
        val resId = subs.values.computeUiStatusResId()
        if (resId == null) {
            statusService.set(StatusSlot.CONNECTION, null)
        } else {
            statusService.set(StatusSlot.CONNECTION, UiText.Res(resId))
        }
    }

    private fun HtspMessage.toSubStatusOrNull(): SubscriptionStatus? {
        val m = method ?: return null
        if (m != "subscriptionStatus" && m != "subscriptionStart") return null

        val id = int("subscriptionId") ?: int("id") ?: return null

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

    @StringRes
    private fun Collection<SubscriptionStatus>.computeUiStatusResId(): Int? {
        if (isEmpty()) return null

        fun norm(v: String?): String =
            v?.lowercase()?.replace(" ", "") ?: ""

        for (s in this) {
            val code = norm(s.subscriptionError ?: s.state)

            return when {
                "invalidtarget" in code -> R.string.tvh_target_invalid
                "nofreeadapter" in code -> R.string.tvh_no_free_adapter
                "muxnotenabled" in code -> R.string.tvh_mux_not_enabled
                "tuningfailed" in code -> R.string.tvh_tuning_failed
                "badsignal" in code -> R.string.tvh_bad_signal
                "scrambled" in code -> R.string.tvh_scrambled
                "subscriptionoverridden" in code -> R.string.tvh_subscription_overridden
                "noinput" in code -> R.string.tvh_no_input
                else -> continue
            }
        }

        return null
    }

    private fun startOrRestartReconnectLoop() {
        reconnectJob?.cancel()

        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val cfg = lastCfg ?: return@launch

                val ok = try {
                    connectInternal(cfg)
                } catch (e: UntrustedCertificateException) {
                    // Self-signed (or otherwise untrusted) certificate: ask the user to
                    // compare and remember the fingerprint. Retrying won't help until then.
                    _certificatePrompt.value = CertificatePrompt(cfg.host, e.fingerprint, previous = null)
                    statusService.set(StatusSlot.CONNECTION, UiText.Res(R.string.tls_waiting_for_trust))
                    return@launch
                } catch (e: CertificateChangedException) {
                    _certificatePrompt.value = CertificatePrompt(cfg.host, e.fingerprint, previous = e.pinned)
                    statusService.set(StatusSlot.CONNECTION, UiText.Res(R.string.tls_certificate_changed))
                    return@launch
                }
                if (ok) return@launch

                statusService.set(StatusSlot.CONNECTION, UiText.Plain("Reconnect in 5s…"))
                delay(5_000)
            }
        }
    }

    fun reconnectNow() = startOrRestartReconnectLoop()

    /** User confirmed the certificate: pin it; the settings change triggers a reconnect. */
    fun trustCertificate(prompt: CertificatePrompt) {
        _certificatePrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveTlsFingerprint(prompt.fingerprint)
        }
    }

    fun rejectCertificate() {
        _certificatePrompt.value = null
        statusService.set(StatusSlot.CONNECTION, UiText.Res(R.string.tls_not_trusted))
    }

    private suspend fun connectInternal(cfg: ServerCfg): Boolean {
        val host = cfg.host
        val port = cfg.htspPort
        return try {
            statusService.set(StatusSlot.SYNC, null)
            statusService.set(StatusSlot.EPG, null)
            statusService.set(StatusSlot.CONNECTION, UiText.Plain("Connecting to $host:$port"))

            repo.onNewConnectionStarting()

            htsp.connect(
                host = host,
                port = port,
                username = cfg.username,
                password = cfg.password,
                tls = cfg.tls,
                tlsFingerprint = cfg.tlsFingerprint.takeIf { it.isNotBlank() },
                forceReconnect = true,
                connectTimeoutMs = 10_000,
                responseTimeoutMs = 5_000
            )
            statusService.set(StatusSlot.CONNECTION, UiText.Plain("Connected"))

            statusService.set(StatusSlot.SYNC, UiText.Plain("Syncing…"))
            htsp.enableAsyncMetadataAndWaitInitialSync()

            repo.awaitChannelsReady()
            repo.startEpgWorker()

            true
        } catch (e: UntrustedCertificateException) {
            throw e
        } catch (e: CertificateChangedException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Connect failed")
            statusService.set(
                StatusSlot.CONNECTION,
                UiText.Plain("Connection failed: ${e.message ?: e}")
            )
            false
        }
    }
}
