package cz.preclikos.tvhstream.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tvh_settings")

data class ServerSettings(
    val host: String = "",
    val htspPort: Int = 9982,
    val httpPort: Int = 9981,
    val username: String = ""
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT_HTSP = intPreferencesKey("htspPort")
        val PORT_HTTP = intPreferencesKey("httpPort")
        val USER = stringPreferencesKey("user")
        val AUTO = booleanPreferencesKey("auto")
    }

    val serverSettings: Flow<ServerSettings> =
        context.dataStore.data.map { p ->
            ServerSettings(
                host = p[Keys.HOST] ?: "",
                htspPort = p[Keys.PORT_HTSP] ?: 9982,
                httpPort = p[Keys.PORT_HTTP] ?: 9981,
                username = p[Keys.USER] ?: ""
            )
        }

    suspend fun saveServer(
        host: String,
        htspPort: Int,
        httpPort: Int,
        username: String,
        autoConnect: Boolean
    ) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = host
            p[Keys.PORT_HTSP] = htspPort
            p[Keys.PORT_HTTP] = httpPort
            p[Keys.USER] = username
            p[Keys.AUTO] = autoConnect
        }
    }
}