package cz.preclikos.tvhstream.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.player.PlayerSession
import cz.preclikos.tvhstream.repositories.TvhRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VideoPlayerViewModel(
    private val playerSession: PlayerSession,
    private val repo: TvhRepository
) : ViewModel() {

    fun getPlayerInstance(context: Context) =
        playerSession.getOrCreatePlayer(context)

    fun playService(context: Context, serviceId: Int) {
        playerSession.playService(context, serviceId)
    }

    fun stop() {
        playerSession.stop()
    }

    fun epgForChannel(channelId: Int) = repo.epgForChannel(channelId)

    fun ensureEpgLoaded(channelId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.loadEpgForChannel(channelId)
        }
    }
}