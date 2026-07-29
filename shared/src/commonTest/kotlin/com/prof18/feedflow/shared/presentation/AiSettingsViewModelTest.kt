package com.prof18.feedflow.shared.presentation

import com.prof18.feedflow.shared.data.ApiKeyStorage
import com.prof18.feedflow.shared.test.KoinTestBase
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsViewModelTest : KoinTestBase() {

    private val storage = RecordingApiKeyStorage()
    private val viewModel: AiSettingsViewModel by inject()

    override fun getTestModules(): List<Module> =
        super.getTestModules() + module {
            single<ApiKeyStorage> { storage }
        }

    @Test
    fun `typing a key stores the last keystroke and not an earlier prefix`() = runTest {
        viewModel.updateApiKey("k")
        viewModel.updateApiKey("ke")
        viewModel.updateApiKey("key")

        assertEquals("key", storage.storedKey)
        assertEquals("key", viewModel.settingsState.value.apiKey)
        assertFalse(viewModel.settingsState.value.hasKeyStorageFailed)
    }

    @Test
    fun `a failed write is surfaced to the user`() = runTest {
        storage.canStore = false

        viewModel.updateApiKey("key")

        assertTrue(viewModel.settingsState.value.hasKeyStorageFailed)
    }
}

private class RecordingApiKeyStorage : ApiKeyStorage {
    var storedKey: String? = null
    var canStore: Boolean = true

    override fun getApiKey(): String? = storedKey

    override fun setApiKey(key: String): Boolean {
        if (!canStore) return false
        storedKey = key
        return true
    }
}
