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
    val port: Int = 9982,
    val username: String = ""
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val USER = stringPreferencesKey("user")
        val AUTO = booleanPreferencesKey("auto")
    }

    val serverSettings: Flow<ServerSettings> =
        context.dataStore.data.map { p ->
            ServerSettings(
                host = p[Keys.HOST] ?: "",
                port = p[Keys.PORT] ?: 9982,
                username = p[Keys.USER] ?: ""
            )
        }

    suspend fun saveServer(host: String, port: Int, username: String, autoConnect: Boolean) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = host
            p[Keys.PORT] = port
            p[Keys.USER] = username
            p[Keys.AUTO] = autoConnect
        }
    }
}