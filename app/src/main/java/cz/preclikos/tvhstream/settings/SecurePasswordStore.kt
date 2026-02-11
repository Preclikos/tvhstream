package cz.preclikos.tvhstream.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secureDataStore by preferencesDataStore(name = "tvh_secure")

class SecurePasswordStore(private val context: Context) {

    private val passwordKey = stringPreferencesKey("password_enc")
    private val keyAlias = "tvh_secure_aes_key"
    
    val passwordFlow: Flow<String> =
        context.secureDataStore.data
            .map { prefs ->
                val enc = prefs[passwordKey] ?: return@map ""
                runCatching { decrypt(enc) }.getOrDefault("")
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun getPassword(): String {
        val enc = context.secureDataStore.data.first()[passwordKey] ?: return ""
        return runCatching { decrypt(enc) }.getOrDefault("")
    }

    suspend fun setPassword(value: String) {
        if (value.isEmpty()) {
            context.secureDataStore.edit { it.remove(passwordKey) }
            return
        }
        val enc = encrypt(value)
        context.secureDataStore.edit { it[passwordKey] = enc }
    }

    suspend fun clear() {
        context.secureDataStore.edit { it.clear() }
    }


    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv // 12B random IV
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val out = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)

        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        if (blob.size < 12) return ""

        val iv = blob.copyOfRange(0, 12)
        val cipherText = blob.copyOfRange(12, blob.size)

        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

        val plain = cipher.doFinal(cipherText)
        return plain.toString(Charsets.UTF_8)
    }
}
