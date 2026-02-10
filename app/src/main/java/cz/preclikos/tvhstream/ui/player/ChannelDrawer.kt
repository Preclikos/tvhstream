package cz.preclikos.tvhstream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.ui.common.progress
import cz.preclikos.tvhstream.ui.components.ChannelRow
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@Composable
fun ChannelDrawer(
    channels: List<ChannelUi>,
    selectedId: Int,
    nowSec: Long,
    channelsVm: ChannelsViewModel,
    onFocusChannel: (Int) -> Unit,
    onPickChannel: (ChannelUi) -> Unit,
    focusRequester: FocusRequester,
    onCloseDrawer: () -> Unit
) {
    val listState = rememberLazyListState()

    var didInitialRestore by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    val selectedRowFocus = remember { FocusRequester() }

    LaunchedEffect(channels, selectedId) {
        if (didInitialRestore) return@LaunchedEffect
        if (channels.isEmpty()) return@LaunchedEffect

        val id = if (selectedId == -1) channels.first().id else selectedId
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

        didInitialRestore = true
        isRestoring = false
    }

    Surface(
        tonalElevation = 6.dp,
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Back, Key.DirectionRight -> {
                        onCloseDrawer(); true
                    }

                    else -> false
                }
            }
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 10.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .focusGroup()
                .focusRestorer()
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                val isSelected = ch.id == selectedId

                val now = remember(ch.id, nowSec) { channelsVm.nowEvent(ch.id, nowSec) }
                val prog = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }

                ChannelRow(
                    modifier = if (isSelected) Modifier.focusRequester(selectedRowFocus) else Modifier,
                    number = index + 1,
                    name = ch.name,
                    programTitle = now?.title ?: stringResource(R.string.no_epg),
                    progress = if (now != null) prog else null,
                    focused = isSelected,
                    onFocus = { if (!isRestoring) onFocusChannel(ch.id) },
                    onConfirm = { onPickChannel(ch) }
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}