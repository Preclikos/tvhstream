package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.repositories.TvhRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class ChannelsViewModel(
    private val htsp: HtspService,
    private val repo: TvhRepository
) : ViewModel() {
    // ✅ UI bere data z repo (single source of truth)
    val channels = repo.channelsUi
    val status = repo.status
}