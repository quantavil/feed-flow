package com.prof18.feedflow.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.core.model.ArticleAiService
import com.prof18.feedflow.core.utils.DispatcherProvider
import com.prof18.feedflow.shared.data.AiSettingsRepository
import com.prof18.feedflow.shared.data.FeedAppearanceSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data object Success : AiTestState
    data class Error(val exception: AiSummaryException) : AiTestState
}

data class AiSettingsState(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val systemPrompt: String,
    val isAiEnabled: Boolean = false,
    val hasKeyStorageFailed: Boolean = false,
)

class AiSettingsViewModel(
    private val aiSettingsRepository: AiSettingsRepository,
    private val articleAiService: ArticleAiService,
    private val feedAppearanceSettingsRepository: FeedAppearanceSettingsRepository,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

    private val mutableSettingsState = MutableStateFlow(
        AiSettingsState(
            apiKey = "",
            model = aiSettingsRepository.getModel(),
            baseUrl = aiSettingsRepository.getBaseUrl(),
            systemPrompt = aiSettingsRepository.getSystemPrompt(),
            isAiEnabled = aiSettingsRepository.isAiEnabled.value,
        ),
    )
    val settingsState: StateFlow<AiSettingsState> = mutableSettingsState.asStateFlow()

    init {
        // Off the main thread: reading the key builds the Keystore-backed store on first use.
        viewModelScope.launch {
            val storedKey = withContext(dispatcherProvider.io) { aiSettingsRepository.getApiKey() }
            mutableSettingsState.update { it.copy(apiKey = storedKey.orEmpty()) }
        }
    }

    private val mutableTestState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    val testState: StateFlow<AiTestState> = mutableTestState.asStateFlow()

    private var apiKeyWriteJob: Job? = null

    fun setAiEnabled(enabled: Boolean) {
        aiSettingsRepository.setAiEnabled(enabled)
        mutableSettingsState.update { it.copy(isAiEnabled = enabled) }
        if (!enabled) {
            // The sort option disappears with the feature, so a timeline left on it would show a
            // selection the user can no longer see or change.
            feedAppearanceSettingsRepository.resetRelevanceOrder()
        }
    }

    fun updateApiKey(key: String) {
        mutableSettingsState.update { it.copy(apiKey = key, hasKeyStorageFailed = false) }
        mutableTestState.update { AiTestState.Idle }
        // Encrypting is real work, so the field updates from state and the write follows.
        // One keystroke is one write, and unserialised they race on the IO dispatcher: a slower
        // earlier write can land last and leave a stale prefix of the key stored. Waiting on the
        // previous one keeps the last keystroke the last write, and drops the ones not yet started.
        val previousWrite = apiKeyWriteJob
        apiKeyWriteJob = viewModelScope.launch {
            previousWrite?.cancelAndJoin()
            val stored = aiSettingsRepository.setApiKey(key)
            mutableSettingsState.update { it.copy(hasKeyStorageFailed = !stored && key.isNotBlank()) }
        }
    }

    fun updateModel(model: String) {
        aiSettingsRepository.setModel(model)
        mutableSettingsState.update { it.copy(model = model) }
        mutableTestState.update { AiTestState.Idle }
    }

    fun updateBaseUrl(baseUrl: String) {
        aiSettingsRepository.setBaseUrl(baseUrl)
        mutableSettingsState.update { it.copy(baseUrl = baseUrl) }
        mutableTestState.update { AiTestState.Idle }
    }

    fun updateSystemPrompt(prompt: String) {
        aiSettingsRepository.setSystemPrompt(prompt)
        mutableSettingsState.update { it.copy(systemPrompt = prompt) }
    }

    fun testConnection() {
        if (settingsState.value.apiKey.isBlank()) {
            mutableTestState.update { AiTestState.Error(AiSummaryException(AiSummaryError.MISSING_API_KEY)) }
            return
        }

        mutableTestState.update { AiTestState.Testing }
        viewModelScope.launch {
            mutableTestState.update {
                try {
                    articleAiService.complete(
                        systemPrompt = settingsState.value.systemPrompt,
                        input = TEST_ARTICLE_TEXT,
                    )
                    AiTestState.Success
                } catch (e: AiSummaryException) {
                    AiTestState.Error(e)
                }
            }
        }
    }

    private companion object {
        const val TEST_ARTICLE_TEXT = "Test connection."
    }
}
