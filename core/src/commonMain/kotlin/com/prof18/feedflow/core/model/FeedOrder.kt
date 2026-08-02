package com.prof18.feedflow.core.model

enum class FeedOrder(val sqlValue: String) {
    NEWEST_FIRST("DESC"),
    OLDEST_FIRST("ASC"),
    MOST_RELEVANT("RELEVANCE"),
}

// Articles with no score yet sort as if they were exactly average, so a freshly synced
// article lands mid-list by date instead of being buried under yesterday's ranked news.
const val NEUTRAL_RELEVANCE_SCORE = 50
const val MIN_RELEVANCE_SCORE = 0
const val MAX_RELEVANCE_SCORE = 100
