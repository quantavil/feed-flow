package com.prof18.feedflow.android.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prof18.feedflow.android.widget.FeedFlowWidget
import com.prof18.feedflow.shared.data.AiSettingsRepository
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    navigateBack: () -> Unit,
    navigateToAppearance: () -> Unit,
    navigateToFeedsAndAccounts: () -> Unit,
    navigateToFeedListSettings: () -> Unit,
    navigateToReadingBehavior: () -> Unit,
    navigateToSyncAndStorage: () -> Unit,
    navigateToWidgetSettings: () -> Unit,
    navigateToAboutAndSupport: () -> Unit,
    navigateToAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion = packageInfo.versionName ?: ""
    var hasWidget by remember { mutableStateOf(false) }

    val aiSettingsRepository: AiSettingsRepository = koinInject()
    val isAiKeySet by aiSettingsRepository.hasApiKey.collectAsStateWithLifecycle()
    val isAiEnabled by aiSettingsRepository.isAiEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(FeedFlowWidget::class.java)
        hasWidget = ids.isNotEmpty()
    }

    SettingsScreenContent(
        appVersion = appVersion,
        navigateBack = navigateBack,
        navigateToAppearance = navigateToAppearance,
        navigateToFeedsAndAccounts = navigateToFeedsAndAccounts,
        navigateToFeedListSettings = navigateToFeedListSettings,
        navigateToReadingBehavior = navigateToReadingBehavior,
        navigateToSyncAndStorage = navigateToSyncAndStorage,
        navigateToWidgetSettings = navigateToWidgetSettings,
        navigateToAboutAndSupport = navigateToAboutAndSupport,
        navigateToAiSettings = navigateToAiSettings,
        showWidgetSettings = hasWidget,
        isAiKeySet = isAiKeySet,
        isAiEnabled = isAiEnabled,
    )
}
