package cz.preclikos.tvhstream.ui.screens

import cz.preclikos.tvhstream.ui.components.EmptyChannelsPane
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.core.ChannelsEmptyState
import cz.preclikos.tvhstream.core.EpgViewport
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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
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
    htsp: HtspService = koinInject(),
    onOpenSettings: () -> Unit = {},
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit
) {
    val selectedId by selection.selectedId.collectAsState()
    val channels by channelViewModel.channels.collectAsState()
    val connection by htsp.state.collectAsState()
    val emptyState = ChannelsEmptyState.resolve(channels.isNotEmpty(), connection)

    
    // Minute resolution is enough for the grid (3.2 dp/min); ticking every few seconds
    // used to recompose every visible row each time.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            nowSec = nowMs / 1000L
            delay(60_000L - nowMs % 60_000L)
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
    val pxPerSec = remember(dpPerMin, density) { with(density) { dpPerMin.toPx() } / 60f }

    // Shared by all rows: the timeline viewport width (measured once) and the scroll
    // position quantized to scroll steps, so rows only re-cull programmes when the
    // viewport crosses a step boundary instead of on every scroll frame.
    val timelineViewportPx = remember { mutableIntStateOf(0) }
    val scrollBucket = remember(stepPx) { derivedStateOf { EpgViewport.bucket(hScroll.value, stepPx) } }

    // Where the timeline starts inside a row: row padding + channel column + spacer.
    val timelineLeft = RowHorizontalPadding + channelColWidth + ChannelTimelineGap
    val nowLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)

    
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
            text = stringResource(R.string.epg_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        if (emptyState != ChannelsEmptyState.NONE) {
            EmptyChannelsPane(state = emptyState, onOpenSettings = onOpenSettings)
            return@Column
        }

        
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
                    // One continuous now-line over all rows, positioned in the draw
                    // phase from the scroll value: no per-row Box, no recomposition.
                    .drawWithContent {
                        drawContent()
                        val nowX = EpgViewport.nowLineXPx(
                            nowSec, windowStartSec, windowEndSec, pxPerSec
                        ) ?: return@drawWithContent
                        val left = timelineLeft.toPx()
                        val right = size.width - RowHorizontalPadding.toPx()
                        val x = left + nowX - hScroll.value
                        if (x < left || x > right) return@drawWithContent
                        val w = NowLineWidth.toPx()
                        drawRect(
                            color = nowLineColor,
                            topLeft = Offset(x - w / 2f, 0f),
                            size = Size(w, size.height)
                        )
                    }
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
                        pxPerSec = pxPerSec,
                        scrollBucket = scrollBucket,
                        timelineViewportPx = timelineViewportPx,
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
    pxPerSec: Float,
    scrollBucket: State<Int>,
    timelineViewportPx: MutableIntState,
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
            .padding(horizontal = RowHorizontalPadding, vertical = 6.dp)
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

        Spacer(Modifier.width(ChannelTimelineGap))

        
        TimelineVisual(
            epg = epg,
            nowSec = nowSec,
            windowStartSec = windowStartSec,
            windowEndSec = windowEndSec,
            dpPerMin = dpPerMin,
            rowHeight = rowHeight,
            hScroll = hScroll,
            pxPerSec = pxPerSec,
            stepPx = stepPx,
            scrollBucket = scrollBucket,
            timelineViewportPx = timelineViewportPx,
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

private val RowHorizontalPadding = 10.dp
private val ChannelTimelineGap = 8.dp
private val NowLineWidth = 2.dp
private const val SlotMinutes = 30

/** Keep at least this much of a programme cell for its title when it sticks to the edge. */
private val MinStickyTitleWidth = 80.dp

/** Programme cell outer padding + inner text padding (see the cell modifiers below). */
private val CellTitleInset = 2.dp + 10.dp

@Composable
private fun TimelineVisual(
    epg: List<EpgEventEntry>,
    nowSec: Long,
    windowStartSec: Long,
    windowEndSec: Long,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    pxPerSec: Float,
    stepPx: Float,
    scrollBucket: State<Int>,
    timelineViewportPx: MutableIntState,
) {
    val totalMin = ((windowEndSec - windowStartSec) / 60L).toInt()
    val totalWidth = dpPerMin * totalMin
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val density = LocalDensity.current
    val slotPx = remember(dpPerMin, density) { with(density) { (dpPerMin * SlotMinutes).toPx() } }
    val fallbackViewportPx = LocalWindowInfo.current.containerSize.width

    Box(
        Modifier
            .fillMaxHeight()
            .onSizeChanged {
                if (it.width > 0 && timelineViewportPx.intValue != it.width) {
                    timelineViewportPx.intValue = it.width
                }
            }
            .horizontalScroll(hScroll)
    ) {
        // Slot grid: one draw call instead of ~48 bordered Boxes per row.
        Box(
            Modifier
                .width(totalWidth)
                .fillMaxHeight()
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(gridColor, Offset.Zero, Size(size.width, stroke))
                    drawRect(gridColor, Offset(0f, size.height - stroke), Size(size.width, stroke))
                    var x = 0f
                    while (x <= size.width) {
                        drawRect(gridColor, Offset(x, 0f), Size(stroke, size.height))
                        x += slotPx
                    }
                }
        )

        // Only compose programmes overlapping the (quantized) visible window.
        val bucket = scrollBucket.value
        val viewportPx = timelineViewportPx.intValue.takeIf { it > 0 } ?: fallbackViewportPx
        val visible = remember(epg, windowStartSec, windowEndSec, bucket, viewportPx, pxPerSec, stepPx) {
            EpgViewport.eventsInRange(
                epg = epg,
                windowStartSec = windowStartSec,
                windowEndSec = windowEndSec,
                pxPerSec = pxPerSec,
                rangePx = EpgViewport.composeRangePx(bucket, stepPx, viewportPx),
            )
        }

        visible.forEach { e ->
            key(e.eventId) {
                ProgrammeCell(
                    e = e,
                    nowSec = nowSec,
                    windowStartSec = windowStartSec,
                    windowEndSec = windowEndSec,
                    dpPerMin = dpPerMin,
                    rowHeight = rowHeight,
                    hScroll = hScroll,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    e: EpgEventEntry,
    nowSec: Long,
    windowStartSec: Long,
    windowEndSec: Long,
    dpPerMin: Dp,
    rowHeight: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
) {
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
            .layout { measurable, constraints ->
                // Position in the layout phase (Dp offset modifier equivalent).
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(x.roundToPx(), 0)
                }
            }
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
            overflow = TextOverflow.Ellipsis,
            // Sticky title: once the programme's start scrolls past the left edge, keep
            // the title at the edge. Reads the scroll value in the layout phase only, so
            // scrolling relayouts the text without recomposing the grid.
            modifier = Modifier.layout { measurable, constraints ->
                val offset = EpgViewport.stickyTitleOffsetPx(
                    scrollPx = hScroll.value,
                    titleStartPx = (x + CellTitleInset).roundToPx(),
                    titleWidthPx = constraints.maxWidth,
                    minTitlePx = MinStickyTitleWidth.roundToPx(),
                )
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - offset).coerceAtLeast(0))
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeRelative(offset, 0)
                }
            }
        )
    }
}
