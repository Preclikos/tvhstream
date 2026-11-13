package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.core.ChannelsEmptyState
import cz.preclikos.tvhstream.core.ConnectionProblem
import cz.preclikos.tvhstream.htsp.ConnectionState
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.settings.ServerSettingsStore
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext

/** The last connection problem worth explaining, plus whether a retry is running now. */
data class ConnectionProblemUi(val problem: ConnectionProblem, val retrying: Boolean)

/**
 * Remembers the last connection error. The reconnect loop flips the state between
 * Error, Disconnected and Connecting every few seconds, so the raw state alone would
 * flicker between "error" and "loading"; the error is kept until a connection succeeds.
 * Returns null without Koin (e.g. in isolated UI tests).
 */
@Composable
fun rememberConnectionProblem(): ConnectionProblemUi? {
    val koin = GlobalContext.getOrNull() ?: return null
    val htsp = remember { koin.get<HtspService>() }
    val settings = remember { koin.get<ServerSettingsStore>() }

    val connection by htsp.state.collectAsState()
    val hostConfigured by remember {
        settings.serverSettings.map { it.host.isNotBlank() }
    }.collectAsState(initial = null)

    var lastError by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(connection) {
        when (val c = connection) {
            is ConnectionState.Error -> lastError = c.throwable
            is ConnectionState.Connected -> lastError = null
            else -> Unit
        }
    }

    val configured = hostConfigured ?: return null
    val problem = ConnectionProblem.classify(configured, lastError) ?: return null
    return ConnectionProblemUi(problem, retrying = connection is ConnectionState.Connecting)
}

/**
 * Shown instead of an empty channel list, so a fresh install / unreachable server
 * isn't just a blank screen. Explains a known connection problem (title + hint) and
 * offers a focused "Open settings" button.
 */
@Composable
fun EmptyChannelsPane(
    state: ChannelsEmptyState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    connectionProblem: ConnectionProblemUi? = rememberConnectionProblem(),
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state == ChannelsEmptyState.NONE -> Unit

            connectionProblem != null -> ProblemMessage(connectionProblem, onOpenSettings)

            state == ChannelsEmptyState.LOADING -> {
                CircularProgressIndicator(Modifier.size(48.dp).testTag("emptyLoading"))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.connecting_to_server),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            else -> Message(
                title = stringResource(R.string.empty_channels_title),
                hint = stringResource(R.string.empty_channels_hint),
                retrying = false,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ProblemMessage(ui: ConnectionProblemUi, onOpenSettings: () -> Unit) {
    val (title, hint) = when (ui.problem) {
        ConnectionProblem.NOT_CONFIGURED -> R.string.conn_not_configured_title to R.string.conn_not_configured_hint
        ConnectionProblem.UNREACHABLE -> R.string.conn_unreachable_title to R.string.conn_unreachable_hint
        ConnectionProblem.TIMEOUT -> R.string.conn_timeout_title to R.string.conn_timeout_hint
        ConnectionProblem.AUTH_FAILED -> R.string.conn_auth_title to R.string.conn_auth_hint
        ConnectionProblem.TLS_UNTRUSTED -> R.string.conn_tls_untrusted_title to R.string.conn_tls_untrusted_hint
        ConnectionProblem.TLS_CHANGED -> R.string.conn_tls_changed_title to R.string.conn_tls_changed_hint
        ConnectionProblem.TLS_FAILED -> R.string.conn_tls_failed_title to R.string.conn_tls_failed_hint
        ConnectionProblem.OTHER -> R.string.conn_other_title to R.string.conn_other_hint
    }
    Message(
        title = stringResource(title),
        hint = stringResource(hint),
        retrying = ui.retrying && ui.problem != ConnectionProblem.NOT_CONFIGURED,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun Message(title: String, hint: String, retrying: Boolean, onOpenSettings: () -> Unit) {
    val buttonFocus = remember { FocusRequester() }
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag("emptyTitle"),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        hint,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 520.dp),
    )
    if (retrying) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.conn_retrying),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onOpenSettings,
        modifier = Modifier
            .focusRequester(buttonFocus)
            .testTag("openSettings"),
    ) {
        Text(stringResource(R.string.open_settings))
    }
    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }
}
