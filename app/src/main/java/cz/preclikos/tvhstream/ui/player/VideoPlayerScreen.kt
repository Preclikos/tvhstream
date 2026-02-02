package cz.preclikos.tvhstream.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.ui.player.AudioTrackDialog
import cz.preclikos.tvhstream.ui.player.SubtitleTrackDialog
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    vm: VideoPlayerViewModel,
    channelId: Int,
    channelName: String,
    serviceId: Int,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val player = remember { vm.getPlayerInstance(ctx) }

    // spustí playback při vstupu / změně serviceId
    LaunchedEffect(serviceId) {
        vm.playService(ctx, serviceId)
    }

    LaunchedEffect(channelId) {
        vm.ensureEpgLoaded(channelId)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.stop()
        }
    }

    KeepScreenOn(enabled = true)

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionToken by remember { mutableIntStateOf(0) }
    var clearFocusToken by remember { mutableIntStateOf(0) }
    val autoHideMs = 5000L

    fun showControls() {
        controlsVisible = true
        interactionToken++
    }

    fun hideControls() {
        clearFocusToken++
        controlsVisible = false
    }

    LaunchedEffect(controlsVisible, interactionToken) {
        if (!controlsVisible) return@LaunchedEffect
        delay(autoHideMs)
        hideControls()
    }

    // EPG list pro kanál
    val epg by vm.epgForChannel(channelId).collectAsState()

    // ticker pro progress + hodiny
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(1000L)
        }
    }

    val nowEvent = remember(epg, nowSec) { epg.nowEvent(nowSec) }
    val nextEvent = remember(epg, nowEvent) { epg.nextAfter(nowEvent) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionUp,
                    Key.DirectionDown,
                    Key.DirectionLeft,
                    Key.DirectionRight,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.DirectionCenter -> {
                        showControls()
                        false
                    }

                    Key.Back -> {
                        if (!controlsVisible) {
                            showControls()
                            true
                        } else {
                            onClose()
                            true
                        }
                    }

                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    controllerAutoShow = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomOverlayControlsTv(
                player = player,
                channelName = channelName,
                nowEvent = nowEvent,
                nextEvent = nextEvent,
                nowSec = nowSec,
                onBack = onClose,
                onUserInteraction = { interactionToken++ },
                clearFocusToken = clearFocusToken
            )
        }
    }
}

@Composable
private fun BottomOverlayControlsTv(
    player: Player,
    channelName: String,
    nowEvent: EpgEventEntry?,
    nextEvent: EpgEventEntry?,
    nowSec: Long,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit,
    clearFocusToken: Int
) {
    val focusManager = LocalFocusManager.current
    val firstButtonRequester = remember { FocusRequester() }

    var showAudio by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        firstButtonRequester.requestFocus()
        onUserInteraction()
    }

    LaunchedEffect(clearFocusToken) {
        focusManager.clearFocus(force = true)
    }

    // hodiny vpravo
    val clock = remember(nowSec) { formatClock(nowSec) }

    // progress a texty
    val progress = remember(nowEvent, nowSec) { nowEvent?.progress(nowSec) ?: 0f }
    val nowTimeRange = remember(nowEvent) { nowEvent?.timeRangeText() }
    val elapsedRemaining = remember(nowEvent, nowSec) { nowEvent?.elapsedRemainingText(nowSec) }

    Surface(
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        color = Color.Black.copy(alpha = 0.45f), // ✅ průhledné "glass"
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: channel + clock
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    channelName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    clock,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // NOW block
            if (nowEvent != null) {
                Text(
                    nowEvent.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        nowTimeRange ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        elapsedRemaining ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )

                if (!nowEvent.summary.isNullOrBlank()) {
                    Text(
                        nowEvent.summary!!,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = Color.White,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    "No EPG data",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // NEXT (menší)
            if (nextEvent != null) {
                val nextRange = remember(nextEvent) { nextEvent.timeRangeText() }
                Text(
                    "Next: ${nextEvent.title} ${if (nextRange != null) "• $nextRange" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Buttons row (TV-friendly)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        onUserInteraction()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(firstButtonRequester)
                ) { Text(if (player.isPlaying) "Pause" else "Play") }

                Button(
                    onClick = { showAudio = true; onUserInteraction() },
                    modifier = Modifier.weight(1f)
                ) { Text("Audio") }

                Button(
                    onClick = { showSubs = true; onUserInteraction() },
                    modifier = Modifier.weight(1f)
                ) { Text("Titulky") }

                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }

    if (showAudio) AudioTrackDialog(player = player, onDismiss = { showAudio = false })
    if (showSubs) SubtitleTrackDialog(player = player, onDismiss = { showSubs = false })
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(enabled) {
        val activity = ctx.findActivity()
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ===== Helpers (EPG) =====

private fun List<EpgEventEntry>.nowEvent(nowSec: Long): EpgEventEntry? =
    firstOrNull { it.start <= nowSec && nowSec < it.stop }
        ?: minByOrNull { kotlin.math.abs(it.start - nowSec) } // fallback, když nemáme přesně teď

private fun List<EpgEventEntry>.nextAfter(now: EpgEventEntry?): EpgEventEntry? {
    val nowId = now?.eventId
    if (isEmpty()) return null
    if (nowId == null) return firstOrNull()
    val idx = indexOfFirst { it.eventId == nowId }
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}

fun EpgEventEntry.progress(nowSec: Long): Float {
    val dur = (stop - start).coerceAtLeast(1L)
    val pos = (nowSec - start).coerceIn(0L, dur)
    return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
}

private fun EpgEventEntry.timeRangeText(): String? {
    // start/stop jsou epoch seconds (u tebe)
    val s = formatClock(start)
    val e = formatClock(stop)
    return "$s–$e"
}

private fun EpgEventEntry.elapsedRemainingText(nowSec: Long): String? {
    val dur = (stop - start).coerceAtLeast(1L)
    val elapsed = (nowSec - start).coerceAtLeast(0L)
    val remaining = (stop - nowSec).coerceAtLeast(0L)

    return "${formatMinutes(elapsed)}/${formatMinutes(dur)} • -${formatMinutes(remaining)}"
}

private fun formatMinutes(sec: Long): String {
    val m = (sec / 60).toInt()
    val h = m / 60
    val mm = m % 60
    return if (h > 0) "${h}h ${mm}m" else "${mm}m"
}

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(epochSec: Long): String {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochSecond(epochSec).atZone(z).format(clockFormatter)
}
