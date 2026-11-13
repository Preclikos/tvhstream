package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/**
 * Shown instead of an empty channel list, so a fresh install / unreachable server
 * isn't just a blank screen. NO_CHANNELS offers a focused "Open settings" button.
 */
@Composable
fun EmptyChannelsPane(
    state: ChannelsEmptyState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ChannelsEmptyState.NONE -> Unit

            ChannelsEmptyState.LOADING -> {
                CircularProgressIndicator(Modifier.size(48.dp).testTag("emptyLoading"))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.connecting_to_server),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            ChannelsEmptyState.NO_CHANNELS -> {
                val buttonFocus = remember { FocusRequester() }
                Text(
                    stringResource(R.string.empty_channels_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.empty_channels_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
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
        }
    }
}
