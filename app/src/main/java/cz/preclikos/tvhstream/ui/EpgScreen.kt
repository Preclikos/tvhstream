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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
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
import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.ui.player.progress
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.util.Calendar

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EpgScreen(
    channelViewModel: ChannelsViewModel = koinViewModel(),
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val channels by channelViewModel.channels.collectAsState()

    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(5_000L)
        }
    }

    var selectedChannelId by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(channels) {
        if (channels.isNotEmpty() && selectedChannelId == -1) {
            selectedChannelId = channels.first().id
        }
    }

    val selectedChannel: ChannelUi? = channels.firstOrNull { it.id == selectedChannelId }

    // Pull full EPG list for selected channel (worker fills it gradually)
    val epgFlow = remember(selectedChannelId) { channelViewModel.epgForChannel(selectedChannelId) }
    val epg by epgFlow.collectAsState()

    val channelListState = rememberLazyListState()
    LaunchedEffect(channels, selectedChannelId) {
        if (channels.isEmpty() || selectedChannelId == -1) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) channelListState.scrollToItem((index - 3).coerceAtLeast(0))
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
            DrawerMenuButton(
                onClick = onOpenDrawer
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.epg_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedChannel?.name ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize()) {

            // LEFT: Channels list
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.40f)
            ) {
                LazyColumn(
                    state = channelListState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                        val focused = ch.id == selectedChannelId
                        val now =
                            remember(ch.id, nowSec) { channelViewModel.nowEvent(ch.id, nowSec) }
                        val progress = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }

                        ChannelPickRow(
                            number = index + 1,
                            name = ch.name,
                            nowTitle = now?.title,
                            progress = if (now != null) progress else null,
                            focused = focused,
                            onFocus = { selectedChannelId = ch.id },
                            onConfirm = { selectedChannelId = ch.id }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // RIGHT: EPG list for selected channel
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.60f)
            ) {
                EpgListPane(
                    channelName = selectedChannel?.name ?: "—",
                    nowSec = nowSec,
                    epg = epg,
                    onPlay = {
                        val ch = selectedChannel ?: return@EpgListPane
                        onPlay(ch.id, ch.id, ch.name)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChannelPickRow(
    number: Int,
    name: String,
    nowTitle: String?,
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
                    text = nowTitle ?: stringResource(R.string.no_epg),
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
private fun EpgListPane(
    channelName: String,
    nowSec: Long,
    epg: List<EpgEventEntry>,
    onPlay: () -> Unit
) {
    val listState = rememberLazyListState()

    // Scroll near "now" when list changes / time moves a lot
    LaunchedEffect(channelName, epg) {
        val idxNow = epg.indexOfFirst { it.start <= nowSec && nowSec < it.stop }
        if (idxNow >= 0) listState.scrollToItem((idxNow - 2).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.epg_schedule),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPlay) {
                Text(stringResource(R.string.play))
            }
        }

        Spacer(Modifier.height(8.dp))

        if (epg.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.epg_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            itemsIndexed(epg, key = { _, e -> e.eventId }) { _, e ->
                val isNow = e.start <= nowSec && nowSec < e.stop
                val progress = remember(e, nowSec) { if (isNow) e.progress(nowSec) else 0f }

                EpgRow(
                    event = e,
                    now = isNow,
                    progress = if (isNow) progress else null
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun EpgRow(
    event: EpgEventEntry,
    now: Boolean,
    progress: Float?
) {
    val bg = if (now) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else MaterialTheme.colorScheme.surface

    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(vertical = 10.dp, horizontal = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatHm(event.start)}–${formatHm(event.stop)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp)
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!event.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = event.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(8.dp))
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

private fun formatHm(unixSec: Long): String {
    val ms = unixSec * 1000L
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}
