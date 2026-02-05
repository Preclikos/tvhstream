package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.repositories.TvhRepository

class ChannelsViewModel(
    private val repo: TvhRepository
) : ViewModel() {
    val channels = repo.channelsUi

    fun nowEvent(channelId: Int, nowSec: Long) = repo.nowEvent(channelId, nowSec)

    fun nextEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        return repo.nextEvent(channelId, nowSec)
    }
}