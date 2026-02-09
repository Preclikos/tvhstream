package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.repositories.TvhRepository
import kotlinx.coroutines.flow.StateFlow

class ChannelsViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repo: TvhRepository
) : ViewModel() {
    val channels = repo.channelsUi

    fun nowEvent(channelId: Int, nowSec: Long) = repo.nowEvent(channelId, nowSec)

    fun nextEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        return repo.nextEvent(channelId, nowSec)
    }

    fun epgForChannel(channelId: Int): StateFlow<List<EpgEventEntry>> =
        repo.epgForChannel(channelId)

    fun setFocusedChannelId(id: Int) {
        savedStateHandle["focused_channel_id"] = id
    }
    val focusedChannelIdFlow = savedStateHandle.getStateFlow("focused_channel_id", -1)

}