package cz.preclikos.tvhstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

@Composable
fun EpgGridScreen(
    channelViewModel: ChannelsViewModel = koinViewModel(),
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit
) {
    val repo: TvhRepository = koinInject()
    val channels by channelViewModel.channels.collectAsState()

    // --- time tick ---
    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(5_000)
        }
    }

    // --- selection (TV focus feel) ---
    var selectedChannelId by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(channels) {
        if (channels.isNotEmpty() && selectedChannelId == -1) selectedChannelId =
            channels.first().id
    }

    // --- timeline settings ---
    val slotMin = 30
    val windowHours = 4
    val windowMin = windowHours * 60
    val dpPerMin: Dp = 3.2.dp
    val rowHeight = 56.dp
    val channelColWidth = 220.dp

    val windowStartSec = remember(nowSec) { floorToMinutes(nowSec, slotMin) }
    val windowEndSec = windowStartSec + (windowMin * 60L)

    // Shared horizontal scroll for header + all rows
    val hScroll = rememberScrollState()

    // ✅ D-pad scroll helpers
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollStepMinutes = 30 // <- změň třeba na 15 nebo 60
    val stepPx = remember(dpPerMin, scrollStepMinutes) {
        with(density) { (dpPerMin * scrollStepMinutes).toPx() }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(channels, selectedChannelId) {
        if (channels.isEmpty() || selectedChannelId == -1) return@LaunchedEffect
        val idx = channels.indexOfFirst { it.id == selectedChannelId }
        if (idx >= 0) listState.scrollToItem((idx - 3).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Text(
            text = "EPG",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize()) {

            // ===== LEFT: channel list =====
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .width(channelColWidth)
                    .fillMaxHeight()
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(channels, key = { it.id }) { ch ->
                        val selected = ch.id == selectedChannelId
                        ChannelCell(
                            channel = ch,
                            selected = selected,
                            onSelect = { selectedChannelId = ch.id },
                            onPlay = { onPlay(ch.id, ch.id, ch.name) },
                            height = rowHeight
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ===== RIGHT: timeline grid =====
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(Modifier.fillMaxSize()) {

                    TimeHeaderRow(
                        windowStartSec = windowStartSec,
                        slotMin = slotMin,
                        windowMin = windowMin,
                        dpPerMin = dpPerMin,
                        rowHeight = 44.dp,
                        hScroll = hScroll
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(channels, key = { it.id }) { ch ->
                            val epgFlow = remember(ch.id) { repo.epgForChannel(ch.id) }
                            val epg by epgFlow.collectAsState()

                            TimelineRow(
                                selected = (ch.id == selectedChannelId),
                                epg = epg,
                                nowSec = nowSec,
                                windowStartSec = windowStartSec,
                                windowEndSec = windowEndSec,
                                dpPerMin = dpPerMin,
                                rowHeight = rowHeight,
                                hScroll = hScroll,
                                stepPx = stepPx,
                                scope = scope,
                                onSelect = { selectedChannelId = ch.id },
                                onPlay = { onPlay(ch.id, ch.id, ch.name) }
                            )

                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(
    channel: ChannelUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    height: Dp
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    val bg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
        else -> Color.Transparent
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .focusable()
            .onKeyEvent { ev ->
                val isSelectKey =
                    (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter)
                if (ev.type == KeyEventType.KeyDown && isSelectKey) {
                    onPlay(); true
                } else false
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TimeHeaderRow(
    windowStartSec: Long,
    slotMin: Int,
    windowMin: Int,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState
) {
    val shape = RoundedCornerShape(14.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val slots = windowMin / slotMin
            repeat(slots + 1) { i ->
                val t = windowStartSec + i * slotMin * 60L
                val label = formatHm(t)
                Box(Modifier.width(dpPerMin * slotMin)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(
    selected: Boolean,
    epg: List<EpgEventEntry>,
    nowSec: Long,
    windowStartSec: Long,
    windowEndSec: Long,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    stepPx: Float,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelect: () -> Unit,
    onPlay: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    val rowBg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    val totalMin = ((windowEndSec - windowStartSec) / 60L).toInt()
    val totalWidth = dpPerMin * totalMin

    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(rowBg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            // ✅ DPAD LEFT/RIGHT scroll the timeline
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (ev.key) {
                    Key.DirectionLeft -> {
                        if (hScroll.value <= 0) return@onPreviewKeyEvent false

                        // jinak scroll doleva
                        scope.launch {
                            val target = (hScroll.value - stepPx).toInt().coerceAtLeast(0)
                            hScroll.animateScrollTo(target)
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        scope.launch {
                            val target = (hScroll.value + stepPx).toInt()
                            hScroll.animateScrollTo(target)
                        }
                        true
                    }

                    else -> false
                }
            }
            .focusable()
            .onKeyEvent { ev ->
                val isSelectKey =
                    (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter)
                if (ev.type == KeyEventType.KeyDown && isSelectKey) {
                    onPlay(); true
                } else false
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
        ) {
            Row(Modifier.width(totalWidth)) {
                val slotMin = 30
                val slots = totalMin / slotMin
                repeat(slots) {
                    Box(
                        Modifier
                            .width(dpPerMin * slotMin)
                            .fillMaxHeight()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                            )
                    )
                }
            }

            if (nowSec in windowStartSec..windowEndSec) {
                val offsetMin = ((nowSec - windowStartSec) / 60f).coerceIn(0f, totalMin.toFloat())
                val x = dpPerMin * offsetMin
                Box(
                    Modifier
                        .offset(x = x, y = 0.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                )
            }

            val visible = remember(epg, windowStartSec, windowEndSec) {
                epg.asSequence()
                    .filter { it.stop > windowStartSec && it.start < windowEndSec }
                    .sortedBy { it.start }
                    .toList()
            }

            visible.forEach { e ->
                val startSec = max(e.start, windowStartSec)
                val stopSec = min(e.stop, windowEndSec)

                val startMin = (startSec - windowStartSec) / 60f
                val durMin = max(1f, (stopSec - startSec) / 60f)

                val x = dpPerMin * startMin
                val w = (dpPerMin * durMin).coerceAtLeast(28.dp)

                val isNow = e.start <= nowSec && nowSec < e.stop
                val bg = if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)

                val border = if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f)

                Box(
                    Modifier
                        .offset(x = x, y = 0.dp)
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                        .height(rowHeight - 12.dp)
                        .width(w)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun floorToMinutes(unixSec: Long, minutes: Int): Long {
    val step = minutes * 60L
    return (unixSec / step) * step
}

private fun formatHm(unixSec: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = unixSec * 1000L }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}
