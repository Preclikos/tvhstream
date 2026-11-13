package cz.preclikos.tvhstream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cz.preclikos.tvhstream.R

/** Buffering / error state of the player, observed from ExoPlayer callbacks. */
data class PlayerStatus(val buffering: Boolean, val error: PlaybackException?)

@Composable
fun rememberPlayerStatus(player: Player): PlayerStatus {
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var error by remember { mutableStateOf(player.playerError) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) error = null
            }

            override fun onPlayerErrorChanged(e: PlaybackException?) {
                error = e
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return PlayerStatus(buffering, error)
}

/**
 * Center overlay over the video: a spinner while buffering, or a "Playback failed"
 * message with a focused Retry button, so a dead stream isn't just a black screen.
 */
@Composable
fun PlayerStatusOverlay(
    status: PlayerStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            status.error != null -> {
                val retryFocus = remember { FocusRequester() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                        .padding(24.dp)
                        .testTag("playerError"),
                ) {
                    Text(
                        stringResource(R.string.playback_failed),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.error.errorCodeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .focusRequester(retryFocus)
                            .testTag("playerRetry"),
                    ) { Text(stringResource(R.string.retry)) }
                }
                LaunchedEffect(status.error) { runCatching { retryFocus.requestFocus() } }
            }

            status.buffering -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("playerBuffering"),
            )
        }
    }
}
