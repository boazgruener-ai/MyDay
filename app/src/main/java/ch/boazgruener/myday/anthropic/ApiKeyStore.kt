/** Encrypts and decrypts the user's own Anthropic API key at rest via Tink/Android Keystore. */
package ch.boazgruener.myday.anthropic

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.apiKeyDataStore by preferencesDataStore(name = "anthropic_api_key")
private val CIPHERTEXT_KEY = stringPreferencesKey("ciphertext")

/**
 * Holds a person's own Anthropic API key encrypted at rest - the one piece of local state in the
 * app actually worth encrypting, since every other store (allowlists, name hints, ...) holds
 * nothing billable if it leaked. Uses Tink directly (an Android-Keystore-backed AEAD) rather than
 * plain DataStore like everything else here, and rather than pulling in androidx.security's
 * EncryptedSharedPreferences, which is just a thin wrapper over the same Tink primitives already
 * a dependency of this project.
 *
 * Exists so each person who sideloads Myday can use their own Anthropic account/billing instead
 * of the original build-time-embedded key - see [AnthropicClient].
 */
class ApiKeyStore(private val context: Context) {
    @Volatile private var cachedAead: Aead? = null

    private suspend fun aead(): Aead = cachedAead ?: withContext(Dispatchers.IO) {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "anthropic_api_key_keyset", "anthropic_api_key_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://myday_anthropic_key_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
            .also { cachedAead = it }
    }

    /**
     * Null if never set, or if the stored ciphertext can no longer be decrypted - e.g. restored
     * via Android Auto Backup onto a different device, where the Keystore master key never
     * travels with the backup, only the ciphertext does. Either case is treated the same: prompt
     * for a key again rather than crash.
     */
    suspend fun getDecryptedApiKey(): String? {
        val ciphertext = context.apiKeyDataStore.data.first()[CIPHERTEXT_KEY] ?: return null
        return try {
            val plainBytes = aead().decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), null)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    suspend fun setApiKey(key: String) {
        val ciphertext = aead().encrypt(key.toByteArray(Charsets.UTF_8), null)
        context.apiKeyDataStore.edit { prefs ->
            prefs[CIPHERTEXT_KEY] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }
    }

    suspend fun clearApiKey() {
        context.apiKeyDataStore.edit { prefs -> prefs.remove(CIPHERTEXT_KEY) }
    }

    /** Presence only - no decrypt, so safe to observe reactively from Compose. */
    fun hasApiKeyFlow(): Flow<Boolean> =
        context.apiKeyDataStore.data.map { prefs -> !prefs[CIPHERTEXT_KEY].isNullOrBlank() }

    /** Never returns the full key - just enough to confirm which one is saved. */
    suspend fun getMaskedApiKey(): String? {
        val key = getDecryptedApiKey() ?: return null
        return if (key.length <= 8) "••••" else "${key.take(7)}…${key.takeLast(4)}"
    }
}
