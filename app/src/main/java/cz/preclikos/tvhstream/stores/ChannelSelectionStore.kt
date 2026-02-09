package cz.preclikos.tvhstream.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChannelSelectionStore {
    private val _selectedId = MutableStateFlow(-1)
    val selectedId: StateFlow<Int> = _selectedId.asStateFlow()

    fun setSelected(id: Int) {
        if (_selectedId.value == id) return
        _selectedId.value = id
    }
}