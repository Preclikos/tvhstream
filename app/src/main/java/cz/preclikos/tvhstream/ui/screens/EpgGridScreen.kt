package cz.preclikos.tvhstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import cz.preclikos.tvhstream.stores.ChannelSelectionStore
import cz.preclikos.tvhstream.ui.common.floorToMinutes
import cz.preclikos.tvhstream.ui.common.formatHm
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.math.min

@Composable
fun EpgGridScreen(
    channelViewModel: ChannelsViewModel = koinViewModel(),
    selection: ChannelSelectionStore = koinInject(),
    repo: TvhRepository = koinInject(),
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit
) {
    val selectedId by selection.selectedId.collectAsState()
    val channels by channelViewModel.channels.collectAsState()

    
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(5_000)
        }
    }

    
    LaunchedEffect(channels) {
        if (channels.isNotEmpty() && selectedId == -1) {
            selection.setSelected(channels.first().id)
        }
    }

    
    val slotMin = 30
    val windowHours = 24
    val windowMin = windowHours * 60
    val dpPerMin: Dp = 3.2.dp
    val rowHeight = 56.dp
    val channelColWidth = 220.dp

    val windowStartSec = remember(nowSec) { floorToMinutes(nowSec, slotMin) }
    val windowEndSec = windowStartSec + (windowMin * 60L)

    
    val hScroll = rememberScrollState()

    
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollStepMinutes = 30
    val stepPx = remember(dpPerMin, scrollStepMinutes) {
        with(density) { (dpPerMin * scrollStepMinutes).toPx() }
    }

    
    val listState = rememberLazyListState()

    
    val selectedRowFocus = remember { FocusRequester() }

    
    var didInitialFocus by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }


    LaunchedEffect(channels, selectedId) {
        if (didInitialFocus) return@LaunchedEffect
        if (channels.isEmpty()) return@LaunchedEffect

        val id = if (selectedId == -1) channels.first().id else selectedId
        if (selectedId == -1) selection.setSelected(id)

        val idx = channels.indexOfFirst { it.id == id }
        if (idx < 0) return@LaunchedEffect

        isRestoring = true

        
        listState.scrollToItem(idx)

        
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == id }
        }.filter { it }.first()

        
        withFrameNanos { }

        
        selectedRowFocus.requestFocus()

        
        withFrameNanos { }

        didInitialFocus = true
        isRestoring = false
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

        
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            TimeHeaderRow(
                windowStartSec = windowStartSec,
                slotMin = slotMin,
                windowMin = windowMin,
                dpPerMin = dpPerMin,
                rowHeight = 44.dp,
                hScroll = hScroll,
                channelColWidth = channelColWidth
            )
        }

        Spacer(Modifier.height(10.dp))

        
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup()
                    .focusRestorer()
            ) {
                items(channels, key = { it.id }) { ch ->
                    val isSelected = ch.id == selectedId

                    val epgFlow = remember(ch.id) { repo.epgForChannel(ch.id) }
                    val epg by epgFlow.collectAsState()

                    EpgGridRow(
                        modifier = if (isSelected) Modifier.focusRequester(selectedRowFocus) else Modifier,
                        channel = ch,
                        selected = isSelected,
                        epg = epg,
                        nowSec = nowSec,
                        windowStartSec = windowStartSec,
                        windowEndSec = windowEndSec,
                        channelColWidth = channelColWidth,
                        dpPerMin = dpPerMin,
                        rowHeight = rowHeight,
                        hScroll = hScroll,
                        stepPx = stepPx,
                        scope = scope,
                        onSelect = {
                            if (!isRestoring) selection.setSelected(ch.id)
                        },
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

@Composable
private fun TimeHeaderRow(
    windowStartSec: Long,
    slotMin: Int,
    windowMin: Int,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    channelColWidth: Dp
) {
    val shape = RoundedCornerShape(14.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        Spacer(Modifier.width(channelColWidth))

        Row(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(hScroll)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val slots = windowMin / slotMin
            repeat(slots + 1) { i ->
                val t = windowStartSec + i * slotMin * 60L
                Box(Modifier.width(dpPerMin * slotMin)) {
                    Text(
                        text = formatHm(t),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgGridRow(
    modifier: Modifier = Modifier,
    channel: ChannelUi,
    selected: Boolean,
    epg: List<EpgEventEntry>,
    nowSec: Long,
    windowStartSec: Long,
    windowEndSec: Long,
    channelColWidth: Dp,
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

    val bg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(bg)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.isFocused) onSelect()
            }
            
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> {
                        if (hScroll.value <= 0) return@onPreviewKeyEvent false
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
            
            .onKeyEvent { ev ->
                val isOk =
                    (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter)
                if (ev.type == KeyEventType.KeyDown && isOk) {
                    onPlay()
                    true
                } else false
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        ChannelNameCell(
            channel = channel,
            selected = selected,
            focused = focused,
            width = channelColWidth
        )

        Spacer(Modifier.width(8.dp))

        
        TimelineVisual(
            epg = epg,
            nowSec = nowSec,
            windowStartSec = windowStartSec,
            windowEndSec = windowEndSec,
            dpPerMin = dpPerMin,
            rowHeight = rowHeight,
            hScroll = hScroll
        )
    }
}

@Composable
private fun ChannelNameCell(
    channel: ChannelUi,
    selected: Boolean,
    focused: Boolean,
    width: Dp
) {
    val cellBg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
        else -> Color.Transparent
    }

    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(cellBg)
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
private fun TimelineVisual(
    epg: List<EpgEventEntry>,
    nowSec: Long,
    windowStartSec: Long,
    windowEndSec: Long,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState
) {
    val totalMin = ((windowEndSec - windowStartSec) / 60L).toInt()
    val totalWidth = dpPerMin * totalMin

    Box(
        Modifier
            .fillMaxHeight()

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
