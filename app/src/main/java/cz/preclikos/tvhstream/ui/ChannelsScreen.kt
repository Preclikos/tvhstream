package cz.preclikos.tvhstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.ui.player.progress
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ChannelsScreen(
    vm: AppConnectionViewModel,
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val channels by vm.channels.collectAsState()

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(5000L)
        }
    }

    var focusedChannelId by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(channels) {
        if (channels.isNotEmpty() && focusedChannelId == -1) {
            focusedChannelId = channels.first().id
        }
    }

    val focusedChannel = channels.firstOrNull { it.id == focusedChannelId }
    val focusedNow = remember(focusedChannelId, nowSec) {
        focusedChannel?.let { vm.nowEvent(it.id, nowSec) }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(channels) {
        if (channels.isEmpty() || focusedChannelId == -1) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == focusedChannelId }
        if (index >= 0) {
            listState.scrollToItem((index - 3).coerceAtLeast(0))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.channel_list),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            TextButton(onClick = onOpenSettings) { Text("Možnosti") }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize()) {


            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.48f)
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                        val isFocused = ch.id == focusedChannelId
                        val now = remember(ch.id, nowSec) { vm.nowEvent(ch.id, nowSec) }
                        val progress = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }

                        ChannelRow(
                            number = index + 1,
                            name = ch.name,
                            programTitle = now?.title ?: "No EPG",
                            progress = if (now != null) progress else null,
                            focused = isFocused,
                            onFocus = { focusedChannelId = ch.id },
                            onConfirm = {
                                onPlay(ch.id, ch.id, ch.name)
                            }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.52f)
            ) {
                EpgDetailPane(
                    channelName = focusedChannel?.name ?: "—",
                    now = focusedNow,
                    nowSec = nowSec
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    name: String,
    programTitle: String,
    progress: Float?,
    focused: Boolean,
    onFocus: () -> Unit,
    onConfirm: () -> Unit
) {
    val bg = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.surface

    val leftBar = if (focused) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)

            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()

            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp &&
                    (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter)
                ) {
                    onConfirm()
                    true
                } else false
            }

            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onConfirm() }
            .padding(vertical = 10.dp, horizontal = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(leftBar)
            )
            Spacer(Modifier.width(10.dp))

            Text(
                text = number.toString().padStart(2, ' '),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp)
            )

            Spacer(Modifier.width(6.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = programTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
            )
        }
    }
}

@Composable
private fun EpgDetailPane(
    channelName: String,
    now: Any?, // <- nechávám Any? aby to šlo zkopírovat; níže cast na tvůj typ
    nowSec: Long
) {


    val e = now as? EpgEventEntry // <-- uprav na svůj balíček/třídu

    val progress = remember(e, nowSec) { e?.progress(nowSec) ?: 0f }

    Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = e?.title ?: "No EPG",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (e != null) {

            val start = remember(e) { e.start }
            val end = remember(e) { e.stop }
            val durSec = (end - start).coerceAtLeast(0)
            val durMin = durSec / 60

            Text(
                "Čas: ${formatHm(start)} – ${formatHm(end)}  •  Doba: ${durMin} min",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.small)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = e.summary ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                text = "Vyber kanál vlevo pro zobrazení detailu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** jednoduché HH:mm z unix seconds (lokální čas) */
private fun formatHm(unixSec: Long): String {
    val ms = unixSec * 1000L
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}
