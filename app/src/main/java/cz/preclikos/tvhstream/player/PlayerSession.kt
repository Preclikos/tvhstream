package cz.preclikos.tvhstream.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.player.htsp.HtspSubscriptionDataSource
import cz.preclikos.tvhstream.player.htsp.LegacyRenderer
import cz.preclikos.tvhstream.player.htsp.TvheadendExtractorsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerSession(
    private val htsp: HtspService
) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private lateinit var dataSourceFactory: HtspSubscriptionDataSource.Factory

    private var subscriptionId: Int? = null

    // State for restoring after process death / rotation
    private var playWhenReadyState = true
    private var currentItem = 0
    private var playbackPosition = 0L

    @OptIn(UnstableApi::class)
    fun getOrCreatePlayer(context: Context): ExoPlayer {
        val renderersFactory = LegacyRenderer(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        return player ?: ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .also { p ->
                p.addAnalyticsListener(EventLogger())
                player = p

                // restore state (na Main)
                p.playWhenReady = playWhenReadyState
                p.seekTo(currentItem, playbackPosition)
            }
    }

    @OptIn(UnstableApi::class)
    fun playService(context: Context, serviceId: Int) {
        //activeServiceId = serviceId

        // zruš předchozí běhy
        //eventCollectorJob?.cancel()
        //subscribeJob?.cancel()

        subscriptionId = null

        // VŠECHNO kolem playeru na MAIN
        mainScope.launch {
            val p = getOrCreatePlayer(context)

            dataSourceFactory = HtspSubscriptionDataSource.Factory(context, htsp, null)
            val mediaSource = ProgressiveMediaSource.Factory(
                dataSourceFactory,
                TvheadendExtractorsFactory()
            )
                .createMediaSource(MediaItem.fromUri("htsp://service/$serviceId"))



            p.setMediaSource(mediaSource)
            p.prepare()
            p.playWhenReady = true // počkáme na subscriptionStart


        }
    }

    fun stop() {
        // stop/release vždy na Main
        mainScope.launch {
            player?.let { p ->
                updateState(p)
                p.stop()
                p.clearMediaItems()
            }
        }
        unsubscribe()
    }

    fun release() {
        // zruš jobs

        dataSourceFactory.releaseCurrentDataSource()
        mainScope.launch {
            player?.let { p ->
                updateState(p)
                p.release()
            }
            player = null
        }

        unsubscribe()

    }

    private fun unsubscribe() {
        mainScope.launch {
            withContext(Dispatchers.IO)
            {
                dataSourceFactory.releaseCurrentDataSource()
            }
        }
    }

    private fun updateState(p: ExoPlayer) {
        playWhenReadyState = p.playWhenReady
        currentItem = p.currentMediaItemIndex
        playbackPosition = p.currentPosition
    }
}
