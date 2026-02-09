package cz.preclikos.tvhstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.stores.ChannelSelectionStore
import cz.preclikos.tvhstream.ui.common.formatHm
import cz.preclikos.tvhstream.ui.components.PiconBox
import cz.preclikos.tvhstream.ui.player.progress
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ChannelsScreen(
    channelViewModel: ChannelsViewModel = koinViewModel(),
    selection: ChannelSelectionStore = koinInject(),
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit
) {
    val channels by channelViewModel.channels.collectAsState()
    val selectedId by selection.selectedId.collectAsState()
    var didInitialRestore by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    val imageLoader = rememberTvhImageLoader()
    val baseUrl = rememberTvhBaseUrl()

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }

    val listState = rememberLazyListState()

    val selectedRowFocus = remember { FocusRequester() }

    val focusedChannel = channels.firstOrNull { it.id == selectedId }
    val focusedNow = remember(selectedId, nowSec) {
        focusedChannel?.let { channelViewModel.nowEvent(it.id, nowSec) }
    }
    val focusedNext = remember(selectedId, nowSec) {
        focusedChannel?.let { channelViewModel.nextEvent(it.id, nowSec) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(5000L)
        }
    }

    LaunchedEffect(channels) {
        if (channels.isEmpty()) return@LaunchedEffect
        if (selectedId == -1) selection.setSelected(channels.first().id)
    }

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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.channel_list),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
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
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier
                        .focusGroup()
                        .focusRestorer()
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                        val isSelected = ch.id == selectedId
                        val now =
                            remember(ch.id, nowSec) { channelViewModel.nowEvent(ch.id, nowSec) }
                        val prog = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }

                        ChannelRow(
                            modifier = if (isSelected) Modifier.focusRequester(selectedRowFocus) else Modifier,
                            number = index + 1,
                            name = ch.name,
                            programTitle = now?.title ?: stringResource(R.string.no_epg),
                            progress = if (now != null) prog else null,
                            focused = isSelected,
                            onFocus = {
                                if (!isRestoring) selection.setSelected(ch.id)
                            },
                            onConfirm = { onPlay(ch.id, ch.id, ch.name) }
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
                    nowSec = nowSec,
                    next = focusedNext,
                    imageLoader = imageLoader,
                    baseUrl = baseUrl,
                    piconPath = focusedChannel?.icon
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    modifier: Modifier = Modifier,
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
        modifier
            .fillMaxWidth()
            .background(bg)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
private fun rememberTvhBaseUrl(settingsStore: SettingsStore = koinInject()): String {
    val s by settingsStore.serverSettings.collectAsState(initial = null)

    val host = s?.host.orEmpty()
    val port = s?.httpPort ?: 9981

    return remember(host, port) {
        if (host.isBlank()) "" else "http://$host:$port"
    }
}

@Composable
fun rememberTvhImageLoader(
    settingsStore: SettingsStore = koinInject(),
    passwordStore: SecurePasswordStore = koinInject()
): ImageLoader {

    val ctx = LocalContext.current
    val settings by settingsStore.serverSettings.collectAsState(initial = null)

    val user = settings?.username.orEmpty()

    val pass by produceState<String?>(initialValue = null, key1 = user) {
        value = if (user.isBlank()) null else passwordStore.getPassword()
    }

    return remember(user, pass) {

        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()

                if (user.isNotBlank() && !pass.isNullOrBlank()) {
                    val auth = Credentials.basic(user, pass!!)
                    chain.proceed(
                        req.newBuilder()
                            .header("Authorization", auth)
                            .build()
                    )
                } else {
                    chain.proceed(req)
                }
            }
            .build()

        ImageLoader.Builder(ctx)
            .components {
                add(OkHttpNetworkFetcherFactory(okHttp))
            }
            .crossfade(true)
            .build()
    }
}

@Composable
private fun EpgDetailPane(
    channelName: String,
    now: EpgEventEntry?,
    next: EpgEventEntry?,
    nowSec: Long,
    imageLoader: ImageLoader,
    baseUrl: String,
    piconPath: String? = null,
) {
    val progress = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }
    Column(Modifier.padding(14.dp)) {

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(Modifier.weight(1f)) {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = now?.title ?: stringResource(R.string.no_epg),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                PiconBox(imageLoader = imageLoader, baseUrl = baseUrl, piconPath = piconPath)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (now != null) {
            val start = remember(now) { now.start }
            val end = remember(now) { now.stop }
            val durMin = ((end - start) / 60).coerceAtLeast(0)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.epg_time_duration,
                        formatHm(start),
                        formatHm(end),
                        durMin.toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.small)
            )
        }

        Spacer(Modifier.height(16.dp))

        if (now?.summary != null) {
            Text(
                text = now.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.weight(1f))

        if (next != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {

                Text(
                    text = stringResource(R.string.epg_next, formatHm(next.start)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = next.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}