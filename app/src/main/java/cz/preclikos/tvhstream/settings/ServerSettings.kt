package cz.preclikos.tvhstream.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "tvh_settings")

data class ServerSettings(
    val host: String = "",
    val htspPort: Int = 9982,
    val username: String = "",
    /** Connect to Tvheadend's encrypted HTSP port (--htsp_tls_port). */
    val tls: Boolean = false,
    /** Pinned SHA-256 fingerprint of a self-signed server certificate ("" = verify via CA). */
    val tlsFingerprint: String = "",
)

class ServerSettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT_HTSP = intPreferencesKey("htspPort")
        val USER = stringPreferencesKey("user")
        val AUTO = booleanPreferencesKey("auto")
        val TLS = booleanPreferencesKey("tls")
        val TLS_FINGERPRINT = stringPreferencesKey("tlsFingerprint")
    }

    val serverSettings: Flow<ServerSettings> =
        context.dataStore.data.map { p ->
            ServerSettings(
                host = p[Keys.HOST] ?: "",
                htspPort = p[Keys.PORT_HTSP] ?: 9982,
                username = p[Keys.USER] ?: "",
                tls = p[Keys.TLS] ?: false,
                tlsFingerprint = p[Keys.TLS_FINGERPRINT] ?: "",
            )
        }

    suspend fun saveServer(
        host: String,
        htspPort: Int,
        username: String,
        autoConnect: Boolean,
        tls: Boolean = false,
        tlsFingerprint: String = "",
    ) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = host
            p[Keys.PORT_HTSP] = htspPort
            p[Keys.USER] = username
            p[Keys.AUTO] = autoConnect
            p[Keys.TLS] = tls
            p[Keys.TLS_FINGERPRINT] = tlsFingerprint
        }
    }

    /** Remembers (pins) the server certificate the user confirmed. */
    suspend fun saveTlsFingerprint(fingerprint: String) {
        context.dataStore.edit { p -> p[Keys.TLS_FINGERPRINT] = fingerprint }
    }
}