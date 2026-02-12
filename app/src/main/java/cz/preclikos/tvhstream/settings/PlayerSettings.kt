package cz.preclikos.tvhstream.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.ProfileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PlayerSettings(
    val profile: String = "",
    val audioLanguage: String?,
    val subtitleLanguage: String?
)

class PlayerSettingsStore(private val context: Context) {

    private object Keys {
        val PROFILE = stringPreferencesKey("profile")
        val AUDIO_LANGUAGE = stringPreferencesKey("audioLanguage")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitleLanguage")
    }

    val playerSettings: Flow<PlayerSettings> =
        context.dataStore.data.map { p ->
            PlayerSettings(
                profile = p[Keys.PROFILE] ?: "",
                audioLanguage = p[Keys.AUDIO_LANGUAGE] ?: "",
                subtitleLanguage = p[Keys.SUBTITLE_LANGUAGE] ?: ""
            )
        }

    suspend fun savePlayer(
        profile: String,
        audioLanguage: String?,
        subtitleLanguage: String?
    ) {
        context.dataStore.edit { p ->
            p[Keys.PROFILE] = profile
            p[Keys.AUDIO_LANGUAGE] = audioLanguage ?: ""
            p[Keys.SUBTITLE_LANGUAGE] = subtitleLanguage ?: ""
        }
    }
}