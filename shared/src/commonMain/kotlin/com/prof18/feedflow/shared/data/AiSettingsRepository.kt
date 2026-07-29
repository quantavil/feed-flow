package com.prof18.feedflow.shared.data

import com.prof18.feedflow.core.model.AiConfig
import com.prof18.feedflow.core.model.DEFAULT_AI_MODEL
import com.prof18.feedflow.core.utils.DispatcherProvider
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

val DEFAULT_SYSTEM_PROMPT = """
    You are a strict summariser. Return plain text only. First give a 2 to 3 sentence summary. Then a line "Key points:" followed by 3 to 5 short bullets. Use UK English. Do not invent facts.
""".trimIndent().replace("\n", " ")

interface ApiKeyStorage {
    fun getApiKey(): String?

    /** @return false when the key could not be persisted, so callers can tell the user. */
    fun setApiKey(key: String): Boolean
}

class AiSettingsRepository(
    private val apiKeyStorage: ApiKeyStorage,
    private val settings: Settings,
    private val dispatcherProvider: DispatcherProvider,
) {
    // Whether a key exists is not itself a secret, so it is mirrored into ordinary settings. The
    // encrypted store is Keystore-backed on Android and building that master key costs tens of
    // milliseconds on the calling thread the first time, which is not something the sort menu or
    // the settings list should pay for on the main thread at startup.
    private val mutableHasApiKey = MutableStateFlow(settings.getBoolean(HAS_API_KEY_KEY, false))

    /**
     * Observed rather than read once. The sort menu is built long before the user first opens
     * the AI settings screen, so a one-shot read leaves "Most Relevant" disabled until some
     * unrelated change happens to make the menu state re-emit.
     */
    val hasApiKey: StateFlow<Boolean> = mutableHasApiKey.asStateFlow()

    private val mutableIsAiEnabled = MutableStateFlow(settings.getBoolean(AI_ENABLED_KEY, false))

    /** Off until the user opts in: AI features cost money and talk to a third party. */
    val isAiEnabled: StateFlow<Boolean> = mutableIsAiEnabled.asStateFlow()

    fun setAiEnabled(enabled: Boolean) {
        settings[AI_ENABLED_KEY] = enabled
        mutableIsAiEnabled.update { enabled }
    }

    /**
     * Suspending because the first read builds the Keystore-backed store, which costs tens of
     * milliseconds. Callers reach this from the timeline, so it must not land on the main thread.
     */
    suspend fun getApiKey(): String? = withContext(dispatcherProvider.io) { apiKeyStorage.getApiKey() }

    /**
     * Suspending because the write is encrypted, which is real work on the calling thread.
     *
     * @return false when the key could not be stored. The mirrored flag is only updated on
     * success, so a failed write surfaces as "No key set" rather than as silence.
     */
    suspend fun setApiKey(key: String): Boolean = withContext(dispatcherProvider.io) {
        val stored = apiKeyStorage.setApiKey(key)
        if (stored) {
            settings[HAS_API_KEY_KEY] = key.isNotBlank()
            mutableHasApiKey.update { key.isNotBlank() }
        }
        stored
    }

    fun getSystemPrompt(): String = settings.readOrDefault(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT)

    fun setSystemPrompt(prompt: String) {
        settings[SYSTEM_PROMPT_KEY] = prompt
    }

    fun getModel(): String = settings.readOrDefault(MODEL_KEY, DEFAULT_AI_MODEL)

    fun setModel(model: String) {
        settings[MODEL_KEY] = model
    }

    fun getRelevanceSignature(): String = settings.getString(RELEVANCE_SIGNATURE_KEY, "")

    fun setRelevanceSignature(signature: String) {
        settings[RELEVANCE_SIGNATURE_KEY] = signature
    }

    suspend fun aiConfig(): AiConfig = AiConfig(
        apiKey = getApiKey(),
        model = getModel(),
    )

    // Clearing a field in the UI stores "", which must fall back to the default rather than
    // building a request against an empty model.
    private fun Settings.readOrDefault(key: String, default: String): String =
        getString(key, default).ifBlank { default }

    private companion object {
        const val SYSTEM_PROMPT_KEY = "ai_system_prompt"
        const val MODEL_KEY = "ai_model"
        const val RELEVANCE_SIGNATURE_KEY = "ai_relevance_signature"
        const val HAS_API_KEY_KEY = "ai_has_api_key"
        const val AI_ENABLED_KEY = "ai_enabled"
    }
}
