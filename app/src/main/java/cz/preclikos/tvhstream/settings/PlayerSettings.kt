package cz.preclikos.tvhstream.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.htsp.ProfileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AspectRatioMode { FIT, FORCE_16_9, FORCE_4_3 }

data class PlayerSettings(
    val profile: String = "",
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val rememberLastPlayedChannel: Boolean = false,
    val lastPlayedChannelId: Int? = null,
)

class PlayerSettingsStore(private val context: Context) {

    private object Keys {
        val PROFILE = stringPreferencesKey("profile")
        val AUDIO_LANGUAGE = stringPreferencesKey("audioLanguage")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitleLanguage")
        val ASPECT_RATIO = stringPreferencesKey("aspectRatio")
        val REMEMBER_LAST_PLAYED_CHANNEL = booleanPreferencesKey("rememberLastPlayedChannel")
        val LAST_PLAYED_CHANNEL_ID = intPreferencesKey("lastPlayedChannelId")
    }

    val playerSettings: Flow<PlayerSettings> =
        context.dataStore.data.map { p ->
            val ar = p[Keys.ASPECT_RATIO]
            val aspect = runCatching { ar?.let(AspectRatioMode::valueOf) }
                .getOrNull() ?: AspectRatioMode.FIT

            PlayerSettings(
                profile = p[Keys.PROFILE] ?: "",
                audioLanguage = p[Keys.AUDIO_LANGUAGE]?.takeIf { it.isNotBlank() },
                subtitleLanguage = p[Keys.SUBTITLE_LANGUAGE]?.takeIf { it.isNotBlank() },
                aspectRatio = aspect,
                rememberLastPlayedChannel = p[Keys.REMEMBER_LAST_PLAYED_CHANNEL] ?: false,
                lastPlayedChannelId = p[Keys.LAST_PLAYED_CHANNEL_ID],
            )
        }

    suspend fun savePlayer(
        profile: String,
        audioLanguage: String?,
        subtitleLanguage: String?,
        aspectRatio: AspectRatioMode,
    ) {
        context.dataStore.edit { p ->
            p[Keys.PROFILE] = profile
            p[Keys.AUDIO_LANGUAGE] = audioLanguage.orEmpty()
            p[Keys.SUBTITLE_LANGUAGE] = subtitleLanguage.orEmpty()
            p[Keys.ASPECT_RATIO] = aspectRatio.name
        }
    }

    suspend fun setProfile(profile: String) {
        context.dataStore.edit { p ->
            p[Keys.PROFILE] = profile
        }
    }

    suspend fun setAspectRatio(aspectRatio: AspectRatioMode) {
        context.dataStore.edit { p ->
            p[Keys.ASPECT_RATIO] = aspectRatio.name
        }
    }

    suspend fun setRememberLastPlayedChannel(enabled: Boolean) {
        context.dataStore.edit { p ->
            p[Keys.REMEMBER_LAST_PLAYED_CHANNEL] = enabled
            if (!enabled) p.remove(Keys.LAST_PLAYED_CHANNEL_ID)
        }
    }

    suspend fun setLastPlayedChannel(channelId: Int) {
        context.dataStore.edit { p ->
            if (p[Keys.REMEMBER_LAST_PLAYED_CHANNEL] == true) {
                p[Keys.LAST_PLAYED_CHANNEL_ID] = channelId
            }
        }
    }
}
