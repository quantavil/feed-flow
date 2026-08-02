package com.prof18.feedflow.shared.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.touchlab.kermit.Logger

// EncryptedSharedPreferences is chosen over a Keystore-wrapped DataStore because it already wraps the
// Android Keystore with AES-256 GCM for key-value storage. A DataStore equivalent would mean hand-rolling
// the Keystore cipher management and serialisation for a single string. The library is deprecated, so the
// version is pinned in libs.versions.toml.

class AndroidEncryptedApiKeyStorage(
    context: Context,
    private val logger: Logger,
) : ApiKeyStorage {

    private val sharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "feedflow_ai_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // A Keystore that has been invalidated (screen lock removed, backup restored onto new
    // hardware) throws here for good. Logging it is the difference between a diagnosable fault
    // and a user retyping a key forever while nothing works.
    @Suppress("TooGenericExceptionCaught")
    override fun getApiKey(): String? {
        return try {
            sharedPreferences.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.e(e) { "Could not read the stored API key" }
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun setApiKey(key: String): Boolean {
        return try {
            sharedPreferences.edit().putString(KEY_API_KEY, key).commit()
        } catch (e: Exception) {
            logger.e(e) { "Could not store the API key" }
            false
        }
    }

    private companion object {
        const val KEY_API_KEY = "gemini_api_key"
    }
}
