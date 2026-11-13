package cz.preclikos.tvhstream.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.player.PlayerSession
import cz.preclikos.tvhstream.repositories.TvhRepository

class VideoPlayerViewModel(
    private val playerSession: PlayerSession,
    private val repo: TvhRepository,
    htspService: HtspService
) : ViewModel() {
    val connectionState = htspService.state

    fun getPlayerInstance(context: Context) =
        playerSession.getOrCreatePlayer(context)

    fun playService(context: Context, serviceId: Int, channelName: String? = null) {
        playerSession.playService(context, serviceId, channelName)
    }

    fun updateNowPlaying(channelName: String, programme: String?) {
        playerSession.updateNowPlaying(channelName, programme)
    }

    fun stop() {
        playerSession.stop()
    }

    override fun onCleared() {
        // Leaving the player screen: free the decoders and the MediaSession; the next
        // player screen builds a fresh ExoPlayer.
        playerSession.release()
        super.onCleared()
    }

    fun epgForChannel(channelId: Int) = repo.epgForChannel(channelId)
}