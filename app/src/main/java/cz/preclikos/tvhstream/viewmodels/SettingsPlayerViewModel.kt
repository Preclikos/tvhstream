package cz.preclikos.tvhstream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.preclikos.tvhstream.htsp.ConnectionState
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.ProfileItem
import cz.preclikos.tvhstream.settings.PlayerSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProfilesUiState {
    data object Idle : ProfilesUiState
    data object Loading : ProfilesUiState
    data class Ready(val items: List<ProfileItem>) : ProfilesUiState
    data class Error(val message: String) : ProfilesUiState
}

data class SettingsPlayerUiState(
    val connected: Boolean = false,
    val profiles: ProfilesUiState = ProfilesUiState.Idle,
    val selectedProfileUuid: String? = null
)

class SettingsPlayerViewModel(
    private val settingsStore: PlayerSettingsStore,
    private val htsp: HtspService,
    private val io: CoroutineDispatcher
) : ViewModel() {


    private val _ui = MutableStateFlow(SettingsPlayerUiState())
    val ui: StateFlow<SettingsPlayerUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            htsp.state.collect { st ->
                _ui.value = _ui.value.copy(connected = st is ConnectionState.Connected)
            }
        }

        viewModelScope.launch {
            htsp.state
                .map { st -> (st as? ConnectionState.Connected)?.let { it.host to it.port } }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) {
                        _ui.value = _ui.value.copy(profiles = ProfilesUiState.Idle)
                        return@collectLatest
                    }

                    _ui.value = _ui.value.copy(profiles = ProfilesUiState.Loading)

                    val result = runCatching { loadProfilesFromServer() }
                    _ui.value = result.fold(
                        onSuccess = { items ->
                            val savedName = settingsStore.playerSettings.first().profile

                            val matchByName = items.firstOrNull { it.name == savedName }

                            val newSelectedUuid =
                                matchByName?.id
                                    ?: _ui.value.selectedProfileUuid
                                    ?: items.firstOrNull()?.id

                            _ui.value.copy(
                                profiles = ProfilesUiState.Ready(items),
                                selectedProfileUuid = newSelectedUuid
                            )
                        },
                        onFailure = { t ->
                            _ui.value.copy(
                                profiles = ProfilesUiState.Error(t.message ?: t.toString())
                            )
                        }
                    )
                }
        }
    }
    fun onProfileSelected(profile: ProfileItem) {
        _ui.value = _ui.value.copy(selectedProfileUuid = profile.id)

        viewModelScope.launch {
            settingsStore.savePlayer(profile.name, "", "")
        }
    }

    private suspend fun loadProfilesFromServer(): List<ProfileItem> = withContext(io) {
        val msg = htsp.request(
            method = "getProfiles",
            fields = emptyMap(),
            timeoutMs = 5_000,
            flush = true,
            disconnectOnTimeout = false
        )

        msg.list("profiles")
            .orEmpty()
            .mapNotNull { item ->
                val m = item.asStringKeyMap() ?: return@mapNotNull null
                val id = m["uuid"] as? String ?: return@mapNotNull null
                val name = m["name"] as? String ?: "Profile $id"
                ProfileItem(id, name)
            }
            .sortedBy { it.name.lowercase() }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asStringKeyMap(): Map<String, Any?>? {
    val m = this as? Map<*, *> ?: return null
    return m.entries
        .filter { it.key is String }
        .associate { it.key as String to it.value }
}
