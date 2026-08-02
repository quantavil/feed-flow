package com.prof18.feedflow.shared.domain.feed.suggestions.bycategory

import com.prof18.feedflow.core.model.SuggestedFeed
import com.prof18.feedflow.core.model.SuggestedFeedCategory

internal val businessFeeds = SuggestedFeedCategory(
    id = "business",
    name = "Business",
    icon = "💼",
    feeds = listOf(
        SuggestedFeed(
            name = "Business Insider",
            url = "https://feeds2.feedburner.com/businessinsider",
            description = "Markets and tech business news",
            logoUrl = "https://www.google.com/s2/favicons?domain=businessinsider.com&sz=64",
        ),
        SuggestedFeed(
            name = "Entrepreneur",
            url = "https://www.entrepreneur.com/latest.rss",
            description = "Small business and startup advice",
            logoUrl = "https://www.google.com/s2/favicons?domain=entrepreneur.com&sz=64",
        ),
        SuggestedFeed(
            name = "Fortune",
            url = "https://fortune.com/feed",
            description = "Business leadership and strategy",
            logoUrl = "https://www.google.com/s2/favicons?domain=fortune.com&sz=64",
        ),
        SuggestedFeed(
            name = "Livemint",
            url = "https://www.livemint.com/rss/news",
            description = "Indian financial markets, business, and economy",
            logoUrl = "https://www.google.com/s2/favicons?domain=livemint.com&sz=64",
        ),
        SuggestedFeed(
            name = "Not Boring",
            url = "https://www.notboring.co/feed",
            description = "Business strategy newsletter",
            logoUrl = "https://www.google.com/s2/favicons?domain=notboring.co&sz=64",
        ),
        SuggestedFeed(
            name = "The Hindu - Business",
            url = "https://www.thehindu.com/business/feeder/default.rss",
            description = "Indian corporate news, economy, and markets",
            logoUrl = "https://www.google.com/s2/favicons?domain=thehindu.com&sz=64",
        ),
        SuggestedFeed(
            name = "The Indian Express - Business",
            url = "https://indianexpress.com/section/business/feed/",
            description = "Indian business, economy, and financial policy",
            logoUrl = "https://www.google.com/s2/favicons?domain=indianexpress.com&sz=64",
        ),
        SuggestedFeed(
            name = "TIME Business",
            url = "https://feeds.feedburner.com/time/business",
            description = "Business and economic coverage",
            logoUrl = "https://www.google.com/s2/favicons?domain=time.com&sz=64",
        ),
    ),
)
