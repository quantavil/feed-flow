package com.prof18.feedflow.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.core.model.ArticleAiService
import com.prof18.feedflow.shared.data.AiSettingsRepository
import com.prof18.feedflow.shared.data.FeedAppearanceSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data object Success : AiTestState
    data class Error(val exception: AiSummaryException) : AiTestState
}

data class AiSettingsState(
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val isAiEnabled: Boolean = false,
    val hasKeyStorageFailed: Boolean = false,
)

class AiSettingsViewModel(
    private val aiSettingsRepository: AiSettingsRepository,
    private val articleAiService: ArticleAiService,
    private val feedAppearanceSettingsRepository: FeedAppearanceSettingsRepository,
    private val logger: Logger,
) : ViewModel() {

    private val mutableSettingsState = MutableStateFlow(
        AiSettingsState(
            apiKey = "",
            model = aiSettingsRepository.getModel(),
            systemPrompt = aiSettingsRepository.getSystemPrompt(),
            isAiEnabled = aiSettingsRepository.isAiEnabled.value,
        ),
    )
    val settingsState: StateFlow<AiSettingsState> = mutableSettingsState.asStateFlow()

    init {
        viewModelScope.launch {
            val storedKey = aiSettingsRepository.getApiKey()
            mutableSettingsState.update { it.copy(apiKey = storedKey.orEmpty()) }
        }
    }

    private val mutableTestState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    val testState: StateFlow<AiTestState> = mutableTestState.asStateFlow()

    private var apiKeyWriteJob: Job? = null
    private var testJob: Job? = null

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

    fun updateSystemPrompt(prompt: String) {
        aiSettingsRepository.setSystemPrompt(prompt)
        mutableSettingsState.update { it.copy(systemPrompt = prompt) }
    }

    fun testConnection() {
        if (settingsState.value.apiKey.isBlank()) {
            mutableTestState.update { AiTestState.Error(AiSummaryException(AiSummaryError.MISSING_API_KEY)) }
            return
        }

        // Cancel any in-flight test so a double-tap cannot bill twice or let an older response
        // overwrite a newer Testing/Success/Error state.
        testJob?.cancel()
        mutableTestState.update { AiTestState.Testing }
        testJob = viewModelScope.launch {
            // The field is the source of truth for the user, but the service reads the key back
            // out of storage. Without waiting for the pending write, testing a freshly pasted key
            // checks whatever the keystore still holds and reports a failure that is not real.
            apiKeyWriteJob?.join()
            // Assigned, never `update {}`: that is a compare-and-set retry loop, and a concurrent
            // edit to any other field resets this flow to Idle, fails the CAS, and re-runs the
            // block. A second billed request per tap is not an acceptable way to lose a race.
            val result = try {
                articleAiService.complete(
                    systemPrompt = settingsState.value.systemPrompt,
                    input = TEST_ARTICLE_TEXT,
                )
                AiTestState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiSummaryException) {
                // The screen shows one line; the stack trace is what says whether the host was
                // unreachable, the key was rejected, or the request never left the device.
                logger.e(e) { "AI test connection failed with ${e.error}: ${e.detail}" }
                AiTestState.Error(e)
            }
            mutableTestState.value = result
        }
    }

    private companion object {
        const val TEST_ARTICLE_TEXT = "Test connection."
    }
}
