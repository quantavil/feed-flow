package com.prof18.feedflow.shared.presentation.model

import com.prof18.feedflow.core.model.FeedOrder

data class HomeViewMenuState(
    val feedOrder: FeedOrder,
    val showReadArticlesTimeline: Boolean,
    // Visible follows the AI switch, available additionally needs a key: the row is hidden
    // outright while AI is off, and shown disabled with a hint when it is on but unusable.
    val isRelevanceSortVisible: Boolean = false,
    val isRelevanceSortAvailable: Boolean = false,
)
