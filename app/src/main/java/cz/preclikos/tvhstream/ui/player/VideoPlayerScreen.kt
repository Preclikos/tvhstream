package cz.preclikos.tvhstream.ui.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import cz.preclikos.tvhstream.stores.ChannelSelectionStore
import cz.preclikos.tvhstream.ui.common.nextAfter
import cz.preclikos.tvhstream.ui.common.nowEvent
import cz.preclikos.tvhstream.ui.components.KeepScreenOn
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    videoPlayerViewModel: VideoPlayerViewModel = koinViewModel(),
    selection: ChannelSelectionStore = koinInject(),
    channelsVm: ChannelsViewModel = koinViewModel(),
    channelId: Int,
    channelName: String,
    serviceId: Int,
    onClose: () -> Unit
) {
    val channels by channelsVm.channels.collectAsState()
    val selectedInitId by selection.selectedId.collectAsState()
    var selectedId by remember { mutableIntStateOf(selectedInitId) }

    var screenActive by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val drawerFocus = remember { FocusRequester() }

    val showDrawer = drawerOpen && !controlsVisible

    var currentChannelId by remember { mutableIntStateOf(channelId) }
    var currentServiceId by remember { mutableIntStateOf(serviceId) }
    var currentChannelName by remember { mutableStateOf(channelName) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val player = remember { videoPlayerViewModel.getPlayerInstance(ctx) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> screenActive = true
                Lifecycle.Event.ON_STOP -> screenActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var lastPlayedServiceId by remember { mutableIntStateOf(-1) }
    LaunchedEffect(screenActive, currentServiceId) {
        if (!screenActive) {
            videoPlayerViewModel.stop()
            lastPlayedServiceId = -1
            return@LaunchedEffect
        }

        if (lastPlayedServiceId == currentServiceId) return@LaunchedEffect

        if (lastPlayedServiceId != -1) {
            videoPlayerViewModel.stop()
        }
        videoPlayerViewModel.playService(ctx, currentServiceId)
        lastPlayedServiceId = currentServiceId
    }

    KeepScreenOn(enabled = true)

    var interactionToken by remember { mutableIntStateOf(0) }
    val autoHideMs = 5000L

    fun showControls() {
        controlsVisible = true
        interactionToken++
    }

    fun hideControls() {
        controlsVisible = false
    }

    LaunchedEffect(controlsVisible, interactionToken) {
        if (!controlsVisible) return@LaunchedEffect
        delay(autoHideMs)
        hideControls()
    }

    val epg by videoPlayerViewModel.epgForChannel(currentChannelId).collectAsState()

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            delay(1000L)
        }
    }

    val nowEvent = remember(epg, nowSec) { epg.nowEvent(nowSec) }
    val nextEvent = remember(epg, nowEvent) { epg.nextAfter(nowEvent) }


    LaunchedEffect(controlsVisible) {
        if (controlsVisible) drawerOpen = false
    }

    LaunchedEffect(showDrawer) {
        if (showDrawer) drawerFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (showDrawer) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionRight,
                        Key.Back -> {
                            drawerOpen = false
                            true
                        }

                        else -> false
                    }
                }

                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!controlsVisible) {
                            selectedId = selectedInitId
                            drawerOpen = true
                            true
                        } else false
                    }

                    Key.Enter,
                    Key.NumPadEnter,
                    Key.DirectionCenter -> {
                        showControls()
                        false
                    }

                    Key.Back -> {
                        when {
                            controlsVisible -> {
                                hideControls()
                                true
                            }

                            else -> {
                                onClose()
                                true
                            }
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
            visible = showDrawer,
            enter = slideInHorizontally(tween(180)) { -it },
            exit = slideOutHorizontally(tween(180)) { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
        ) {
            ChannelDrawer(
                channels = channels,
                selectedId = selectedId,
                nowSec = nowSec,
                channelsVm = channelsVm,
                onFocusChannel = { selectedId = it },
                onPickChannel = {
                    selection.setSelected(it.id)
                    selectedId = it.id

                    currentChannelId = it.id
                    currentServiceId = it.id
                    currentChannelName = it.name

                    drawerOpen = false
                    showControls()
                },
                focusRequester = drawerFocus,
                onCloseDrawer = { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            OverlayControlsTv(
                player = player,
                channelName = currentChannelName,
                nowEvent = nowEvent,
                nextEvent = nextEvent,
                nowSec = nowSec,
                controlsVisible = controlsVisible,
                onBack = onClose,
                onUserInteraction = { interactionToken++ }
            )
        }
    }
}
