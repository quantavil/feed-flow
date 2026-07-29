package com.prof18.feedflow.core.model

data class FeedItemDisplaySettings(
    val isHideUnreadDotEnabled: Boolean = false,
    val isHideFeedSourceEnabled: Boolean = false,
    val descriptionLineLimit: DescriptionLineLimit = DescriptionLineLimit.THREE,
    // Drives the top-story marker, and suppresses "mark above/below as read": those are resolved
    // by publication date, which only matches what the user sees while the list is date-ordered.
    val isRelevanceSortActive: Boolean = false,
)
