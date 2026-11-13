package cz.preclikos.tvhstream.ui.recordings

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.player.htsp.LegacyRenderer
import cz.preclikos.tvhstream.recordings.HtspFileDataSource
import cz.preclikos.tvhstream.recordings.RecordingsRepository
import cz.preclikos.tvhstream.ui.components.KeepScreenOn
import org.koin.compose.koinInject

/**
 * Plays a recording read over HTSP (fileOpen "dvr/<id>"). PlayerView's own controller
 * handles seek and pause on the D-pad; Back returns to the list (AppRoot pops).
 * LegacyRenderer keeps MP1/MP2 on FFmpeg and DVB subtitles working, as in live TV.
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(
    recordingId: Int,
    htsp: HtspService = koinInject(),
    repo: RecordingsRepository = koinInject(),
) {
    val context = LocalContext.current
    val recordings by repo.recordings.collectAsState()
    // Resolve once; the entry may update while playing (e.g. still recording).
    val recording = remember(recordingId) { recordings.firstOrNull { it.id == recordingId } }
    var error by remember { mutableStateOf<PlaybackException?>(null) }

    val player = remember(recordingId) {
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                LegacyRenderer(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
    }

    DisposableEffect(player, recording) {
        val listener = object : Player.Listener {
            override fun onPlayerErrorChanged(e: PlaybackException?) {
                error = e
            }
        }
        player.addListener(listener)
        if (recording != null) {
            val item = MediaItem.Builder()
                .setUri("htsp://dvr/${recording.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(recording.title)
                        .setStation(recording.channelName)
                        .build()
                )
                .build()
            val source = ProgressiveMediaSource.Factory(
                HtspFileDataSource.Factory(htsp, recording),
                DefaultExtractorsFactory(), // recordings are Matroska or MPEG-TS
            ).createMediaSource(item)
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause when the app goes to the background, like the live player stops.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KeepScreenOn(enabled = true)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    isFocusable = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val message = when {
            recording == null -> stringResource(R.string.recordings_not_found)
            error != null -> stringResource(R.string.recordings_play_failed)
            else -> null
        }
        if (message != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                    .padding(24.dp),
            ) {
                Text(message, style = MaterialTheme.typography.titleLarge, color = Color.White)
                error?.let {
                    Text(
                        it.errorCodeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
