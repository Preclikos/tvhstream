package cz.preclikos.tvhstream.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

val topGradient = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.92f),
    0.35f to Color.Black.copy(alpha = 0.70f),
    0.70f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Transparent
)

val bottomGradient = Brush.verticalGradient(
    0f to Color.Transparent,
    0.35f to Color.Black.copy(alpha = 0.35f),
    0.70f to Color.Black.copy(alpha = 0.75f),
    1f to Color.Black.copy(alpha = 0.92f)
)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    vm: VideoPlayerViewModel,
    channelId: Int,
    channelName: String,
    serviceId: Int,
    onClose: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val ctx = LocalContext.current
    val player = remember { vm.getPlayerInstance(ctx) }

    LaunchedEffect(serviceId) {
        vm.playService(ctx, serviceId)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.stop()
        }
    }


    DisposableEffect(lifecycleOwner, serviceId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    vm.stop()   // ideálně stop + release
                }

                Lifecycle.Event.ON_RESUME -> {

                    vm.playService(ctx, serviceId)
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    val epg by vm.epgForChannel(channelId).collectAsState()

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
            KodiOverlayControlsTv(
                player = player,
                channelName = channelName,
                nowEvent = nowEvent,
                nextEvent = nextEvent,
                nowSec = nowSec,
                controlsVisible = controlsVisible,   // ✅ přidáno
                onBack = onClose,
                onUserInteraction = { interactionToken++ }
            )
        }
    }
}

@Composable
private fun KodiOverlayControlsTv(
    player: Player,
    channelName: String,
    nowEvent: EpgEventEntry?,
    nextEvent: EpgEventEntry?,
    nowSec: Long,
    controlsVisible: Boolean,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit
) {
    var showAudio by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }

    var lastFocused by rememberSaveable { mutableIntStateOf(0) }

    val stopFR = remember { FocusRequester() }
    val audioFR = remember { FocusRequester() }
    val subsFR = remember { FocusRequester() }
    val focusRequesters = remember { listOf(stopFR, audioFR, subsFR) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            focusRequesters.getOrNull(lastFocused)?.requestFocus()
        }
    }

    val clock = remember(nowSec) { formatClock(nowSec) }
    val endsAt = remember(nowEvent) { nowEvent?.let { formatClock(it.stop) } ?: "" }
    val progress = remember(nowEvent, nowSec) { nowEvent?.progress(nowSec) ?: 0f }

    val centerTimeText = remember(nowEvent, nowSec) {
        nowEvent?.let { event ->
            val elapsed = (nowSec - event.start).coerceAtLeast(0L)
            val total = (event.stop - event.start).coerceAtLeast(1L)
            "${formatHms(elapsed)} / ${formatHms(total)}"
        } ?: "—"
    }

    val title = remember(nowEvent, channelName) { nowEvent?.title ?: channelName }
    val summary = remember(nowEvent) { nowEvent?.summary?.trim().orEmpty() }

    Box(Modifier.fillMaxSize()) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(topGradient)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (summary.isNotEmpty()) {
                        Text(
                            text = summary,
                            color = Color.White.copy(alpha = 0.86f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (endsAt.isNotEmpty()) {
                        Text(
                            "Končí v: $endsAt",
                            color = Color.White.copy(alpha = 0.90f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(bottomGradient)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {

                RoundIconButton(
                    icon = { Icon(Icons.Filled.Stop, contentDescription = "Stop") },
                    onClick = { onUserInteraction(); onBack() },
                    focusRequester = stopFR,
                    onFocused = { lastFocused = 0 }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = centerTimeText,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )

                    if (nextEvent != null) {
                        val nextRange = remember(nextEvent) { nextEvent.timeRangeText() ?: "" }
                        Text(
                            text = "Následuje: ${nextEvent.title}${if (nextRange.isNotEmpty()) " • $nextRange" else ""}",
                            color = Color.White.copy(alpha = 0.80f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Audio"
                            )
                        },
                        onClick = { onUserInteraction(); showAudio = true },
                        focusRequester = audioFR,
                        onFocused = { lastFocused = 1 }
                    )
                    RoundIconButton(
                        icon = { Icon(Icons.Filled.Subtitles, contentDescription = "Subtitles") },
                        onClick = { onUserInteraction(); showSubs = true },
                        focusRequester = subsFR,
                        onFocused = { lastFocused = 2 }
                    )
                }
            }
        }
    }

    if (showAudio) AudioTrackDialog(player = player, onDismiss = { showAudio = false })
    if (showSubs) SubtitleTrackDialog(player = player, onDismiss = { showSubs = false })
}

@Composable
private fun RoundIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = if (focused) {
        Color.White.copy(alpha = 0.26f)   // fokus výraznější, ale decentní
    } else {
        Color.White.copy(alpha = 0.14f)
    }

    Surface(
        color = Color.Transparent,
        contentColor = Color.White,
        modifier = Modifier
            .size(46.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { st ->
                focused = st.isFocused
                if (st.isFocused) onFocused()
            }
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            icon()
        }
    }
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
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}


private fun List<EpgEventEntry>.nowEvent(nowSec: Long): EpgEventEntry? =
    firstOrNull { it.start <= nowSec && nowSec < it.stop }
        ?: minByOrNull { abs(it.start - nowSec) } // fallback, když nemáme přesně teď

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

private fun formatHms(sec: Long): String {
    val s = sec.coerceAtLeast(0L)
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val ss = (s % 60).toInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}
