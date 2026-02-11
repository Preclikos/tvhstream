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

    fun playService(context: Context, serviceId: Int) {
        playerSession.playService(context, serviceId)
    }

    fun stop() {
        playerSession.stop()
    }

    fun epgForChannel(channelId: Int) = repo.epgForChannel(channelId)
}