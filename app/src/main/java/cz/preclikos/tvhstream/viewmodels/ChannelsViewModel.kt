package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.repositories.TvhRepository

class ChannelsViewModel(
    private val htsp: HtspService,
    private val repo: TvhRepository
) : ViewModel() {

}