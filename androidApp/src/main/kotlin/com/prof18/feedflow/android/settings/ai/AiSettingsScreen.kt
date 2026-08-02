package com.prof18.feedflow.android.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prof18.feedflow.android.settings.SettingsE2eIds
import com.prof18.feedflow.core.model.DEFAULT_AI_MODEL
import com.prof18.feedflow.shared.presentation.AiSettingsState
import com.prof18.feedflow.shared.presentation.AiSettingsViewModel
import com.prof18.feedflow.shared.presentation.AiTestState
import com.prof18.feedflow.shared.ui.utils.LocalFeedFlowStrings
import com.prof18.feedflow.shared.ui.utils.aiErrorMessage
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun AiSettingsScreen(
    navigateBack: () -> Unit,
    viewModel: AiSettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()

    AiSettingsScreenContent(
        settings = settings,
        testState = testState,
        callbacks = AiSettingsCallbacks(
            onAiEnabledChange = viewModel::setAiEnabled,
            onApiKeyChange = viewModel::updateApiKey,
            onModelChange = viewModel::updateModel,
            onSystemPromptChange = viewModel::updateSystemPrompt,
            onTestConnection = viewModel::testConnection,
            navigateBack = navigateBack,
        ),
    )
}

@Composable
private fun AiSettingsScreenContent(
    settings: AiSettingsState,
    testState: AiTestState,
    callbacks: AiSettingsCallbacks,
) {
    val strings = LocalFeedFlowStrings.current
    var isApiKeyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsAiTitle) },
                navigationIcon = {
                    IconButton(onClick = callbacks.navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.settingsAiEnable,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = strings.settingsAiEnableHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    modifier = Modifier.testTag(SettingsE2eIds.AI_ENABLE_SWITCH),
                    checked = settings.isAiEnabled,
                    onCheckedChange = callbacks.onAiEnabledChange,
                )
            }

            // Everything below is inert while the switch is off, rather than hidden, so the user
            // can see what turning it on would involve.
            if (!settings.isAiEnabled) {
                return@Column
            }

            SectionHeader(strings.settingsAiSectionProvider)

            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = callbacks.onApiKeyChange,
                label = { Text(strings.settingsAiApiKey) },
                placeholder = { Text(strings.settingsAiApiKeyPlaceholder) },
                isError = settings.hasKeyStorageFailed,
                supportingText = {
                    Text(
                        if (settings.hasKeyStorageFailed) {
                            strings.settingsAiKeySaveFailed
                        } else {
                            strings.settingsAiKeySharedHint
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsE2eIds.AI_API_KEY_FIELD),
                singleLine = true,
                visualTransformation = if (isApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (isApiKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                        Icon(imageVector = image, contentDescription = null)
                    }
                },
            )

            OutlinedTextField(
                value = settings.model,
                onValueChange = callbacks.onModelChange,
                label = { Text(strings.settingsAiModel) },
                supportingText = { Text(strings.settingsAiDefaultHint(DEFAULT_AI_MODEL)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            Button(
                onClick = callbacks.onTestConnection,
                enabled = settings.apiKey.isNotBlank() && testState !is AiTestState.Testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // A label rather than a spinner: a CircularProgressIndicator has its own intrinsic
                // size and sits off-centre against the button's text baseline.
                Text(
                    if (testState is AiTestState.Testing) {
                        strings.settingsAiTesting
                    } else {
                        strings.settingsAiTestButton
                    },
                )
            }

            when (val currentTestState = testState) {
                is AiTestState.Success -> Text(
                    text = strings.settingsAiTestSuccess,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )

                is AiTestState.Error -> Text(
                    text = strings.settingsAiTestFailed(strings.aiErrorMessage(currentTestState.exception)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> Unit
            }

            SectionHeader(strings.settingsAiSectionSummary)

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = callbacks.onSystemPromptChange,
                label = { Text(strings.settingsAiSystemPrompt) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private data class AiSettingsCallbacks(
    val onAiEnabledChange: (Boolean) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onModelChange: (String) -> Unit,
    val onSystemPromptChange: (String) -> Unit,
    val onTestConnection: () -> Unit,
    val navigateBack: () -> Unit,
)
