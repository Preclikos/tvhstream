package cz.preclikos.tvhstream.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.settings.AspectRatioMode
import cz.preclikos.tvhstream.ui.common.formatClock
import cz.preclikos.tvhstream.ui.common.formatHms
import cz.preclikos.tvhstream.ui.common.progress
import cz.preclikos.tvhstream.ui.components.RoundIconButton

@Composable
fun OverlayControlsTv(
    player: Player,
    channelName: String,
    nowEvent: EpgEventEntry?,
    nextEvent: EpgEventEntry?,
    nowSec: Long,
    controlsVisible: Boolean,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit,
    aspectRatio: AspectRatioMode,
    onAspectRatioChange: () -> Unit,
) {
    var showAudio by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }

    var lastFocused by rememberSaveable { mutableIntStateOf(0) }

    val stopFR = remember { FocusRequester() }
    val audioFR = remember { FocusRequester() }
    val subsFR = remember { FocusRequester() }
    val aspectFR = remember { FocusRequester() }
    val focusRequesters = remember { listOf(stopFR, audioFR, subsFR, aspectFR) }

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
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (endsAt.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                id = R.string.player_ends_in, endsAt
                            ),
                            color = Color.White.copy(alpha = 0.90f),
                            style = MaterialTheme.typography.titleMedium
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress + čas
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

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (nextEvent != null) {
                        val nextRange = remember(nextEvent) { nextEvent.timeRangeText() ?: "" }
                        val text = if (nextRange.isNotEmpty()) {
                            stringResource(
                                R.string.player_next_event_with_range,
                                nextEvent.title,
                                nextRange
                            )
                        } else {
                            stringResource(R.string.player_next_event, nextEvent.title)
                        }
                        Text(
                            text = text,
                            color = Color.White.copy(alpha = 0.80f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 120.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {


                        RoundIconButton(
                            icon = {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = stringResource(R.string.stop),
                                )
                            },
                            onClick = { onUserInteraction(); onBack() },
                            focusRequester = stopFR,
                            onFocused = { lastFocused = 0 }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RoundIconButton(
                                icon = {
                                    Box(contentAlignment = Alignment.Center) {
                                        Canvas(modifier = Modifier.size(28.dp)) {
                                            val strokeWidth = 2.5f
                                            val cap = StrokeCap.Round
                                            val color = Color.White
                                            val s = size.width * 0.28f

                                            drawLine(
                                                color,
                                                Offset(0f, s),
                                                Offset(0f, 0f),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(0f, 0f),
                                                Offset(s, 0f),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(size.width - s, 0f),
                                                Offset(size.width, 0f),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(size.width, 0f),
                                                Offset(size.width, s),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(0f, size.height - s),
                                                Offset(0f, size.height),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(0f, size.height),
                                                Offset(s, size.height),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(size.width - s, size.height),
                                                Offset(size.width, size.height),
                                                strokeWidth,
                                                cap
                                            )
                                            drawLine(
                                                color,
                                                Offset(size.width, size.height - s),
                                                Offset(size.width, size.height),
                                                strokeWidth,
                                                cap
                                            )
                                        }
                                        Text(
                                            text = when (aspectRatio) {
                                                AspectRatioMode.FIT -> "AUTO"
                                                AspectRatioMode.FORCE_16_9 -> "16:9"
                                                AspectRatioMode.FORCE_4_3 -> "4:3"
                                            },
                                            color = Color.White,
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                onClick = { onUserInteraction(); onAspectRatioChange() },
                                focusRequester = aspectFR,
                                onFocused = { lastFocused = 3 }
                            )
                            RoundIconButton(
                                icon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = stringResource(R.string.audio_track)
                                    )
                                },
                                onClick = { onUserInteraction(); showAudio = true },
                                focusRequester = audioFR,
                                onFocused = { lastFocused = 1 }
                            )
                            RoundIconButton(
                                icon = {
                                    Icon(
                                        Icons.Filled.Subtitles,
                                        contentDescription = stringResource(R.string.subtitles)
                                    )
                                },
                                onClick = { onUserInteraction(); showSubs = true },
                                focusRequester = subsFR,
                                onFocused = { lastFocused = 2 }
                            )
                        }
                    }
                }

            }
        }
    }

    if (showAudio) AudioTrackDialog(player = player, onDismiss = { showAudio = false })
    if (showSubs) SubtitleTrackDialog(player = player, onDismiss = { showSubs = false })
}


private fun EpgEventEntry.timeRangeText(): String? {
    val s = formatClock(start)
    val e = formatClock(stop)
    return "$s–$e"
}
