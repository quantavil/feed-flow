package com.prof18.feedflow.android.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.prof18.feedflow.core.model.FeedOrder
import com.prof18.feedflow.shared.presentation.model.HomeViewMenuState
import com.prof18.feedflow.shared.ui.style.Spacing
import com.prof18.feedflow.shared.ui.utils.LocalFeedFlowStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeViewOptionsBottomSheet(
    state: HomeViewMenuState,
    onFeedOrderChange: (FeedOrder) -> Unit,
    onShowReadArticlesTimelineChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val strings = LocalFeedFlowStrings.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.regular)
                .padding(bottom = Spacing.regular),
        ) {
            Text(
                text = strings.settingsFeedOrderTitle,
                modifier = Modifier.padding(bottom = Spacing.small),
            )

            FeedOrderOption(
                label = strings.settingsFeedOrderNewestFirst,
                selected = state.feedOrder == FeedOrder.NEWEST_FIRST,
                onClick = { onFeedOrderChange(FeedOrder.NEWEST_FIRST) },
            )

            FeedOrderOption(
                label = strings.settingsFeedOrderOldestFirst,
                selected = state.feedOrder == FeedOrder.OLDEST_FIRST,
                onClick = { onFeedOrderChange(FeedOrder.OLDEST_FIRST) },
            )

            // Absent entirely while AI is switched off, which is the default: someone who has
            // opted out should not be shown the feature at all.
            if (state.isRelevanceSortVisible) {
                FeedOrderOption(
                    label = strings.settingsFeedOrderMostRelevant,
                    selected = state.feedOrder == FeedOrder.MOST_RELEVANT,
                    onClick = { onFeedOrderChange(FeedOrder.MOST_RELEVANT) },
                    // Without a key this sort silently behaves like "newest first", which reads as
                    // broken. Say why instead of letting the user pick a dead option.
                    enabled = state.isRelevanceSortAvailable,
                    supportingText = if (state.isRelevanceSortAvailable) {
                        strings.settingsFeedOrderMostRelevantHint
                    } else {
                        strings.settingsFeedOrderMostRelevantNoKey
                    },
                )
            }

            Spacer(modifier = Modifier.height(Spacing.regular))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.settingsToggleShowReadArticles,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.showReadArticlesTimeline,
                    onCheckedChange = onShowReadArticlesTimelineChange,
                )
            }
        }
    }
}

@Composable
private fun FeedOrderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = Spacing.small)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                )
            }
        }
    }
}
