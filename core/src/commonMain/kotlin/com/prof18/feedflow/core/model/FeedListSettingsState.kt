package com.prof18.feedflow.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class FeedListSettingsState(
    val isHideDescriptionEnabled: Boolean = false,
    val isHideImagesEnabled: Boolean = false,
    val isHideDateEnabled: Boolean = false,
    val dateFormat: DateFormat = DateFormat.NORMAL,
    val timeFormat: TimeFormat = TimeFormat.HOURS_24,
    val feedLayout: FeedLayout = FeedLayout.LIST,
    val isGridLayoutEnabled: Boolean = false,
    val fontScale: Int = 0,
    val leftSwipeActionType: SwipeActionType = SwipeActionType.TOGGLE_READ_STATUS,
    val rightSwipeActionType: SwipeActionType = SwipeActionType.TOGGLE_BOOKMARK_STATUS,
    val isRemoveTitleFromDescriptionEnabled: Boolean = false,
    val feedOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    // Relevance is only offered once the user has switched AI on, so the dropdown never lists an
    // order the rest of the app is hiding.
    val isRelevanceSortVisible: Boolean = false,
    val isHideUnreadDotEnabled: Boolean = false,
    val isHideFeedSourceEnabled: Boolean = false,
    val descriptionLineLimit: DescriptionLineLimit = DescriptionLineLimit.THREE,
)
