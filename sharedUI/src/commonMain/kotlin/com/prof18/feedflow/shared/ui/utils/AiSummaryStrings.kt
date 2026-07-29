package com.prof18.feedflow.shared.ui.utils

import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.i18n.FeedFlowStrings

fun FeedFlowStrings.aiErrorMessage(exception: AiSummaryException): String = when (exception.error) {
    AiSummaryError.MISSING_API_KEY -> settingsAiSetKeyHint
    AiSummaryError.TIMEOUT -> aiErrorTimeout
    AiSummaryError.NETWORK -> aiErrorNetwork
    AiSummaryError.HTTP -> aiErrorProvider(exception.detail.orEmpty())
    AiSummaryError.PARSE -> aiErrorParse
    AiSummaryError.BLOCKED -> aiErrorBlocked
    AiSummaryError.SAFETY -> aiErrorSafety
    AiSummaryError.TOKEN_LIMIT -> aiErrorTokenLimit
    AiSummaryError.EMPTY -> aiErrorEmpty
}
