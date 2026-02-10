package cz.preclikos.tvhstream.ui.player

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.EpgEventEntry
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
                            text = stringResource(
                                id = R.string.player_ends_in, endsAt
                            ),
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
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom
            ) {

                RoundIconButton(
                    icon = { Icon(Icons.Filled.Stop, contentDescription = "Stop") },
                    onClick = { onUserInteraction(); onBack() },
                    focusRequester = stopFR,
                    onFocused = { lastFocused = 0 })

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
                        val text = if (nextRange.isNotEmpty()) {
                            stringResource(
                                id = R.string.player_next_event_with_range,
                                nextEvent.title,
                                nextRange
                            )
                        } else {
                            stringResource(
                                id = R.string.player_next_event, nextEvent.title
                            )
                        }
                        Text(
                            text = text,
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
                            Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Audio"
                        )
                    },
                        onClick = { onUserInteraction(); showAudio = true },
                        focusRequester = audioFR,
                        onFocused = { lastFocused = 1 })
                    RoundIconButton(
                        icon = { Icon(Icons.Filled.Subtitles, contentDescription = "Subtitles") },
                        onClick = { onUserInteraction(); showSubs = true },
                        focusRequester = subsFR,
                        onFocused = { lastFocused = 2 })
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
