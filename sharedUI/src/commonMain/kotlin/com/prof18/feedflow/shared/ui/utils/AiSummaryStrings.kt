package com.prof18.feedflow.shared.ui.utils

import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.i18n.FeedFlowStrings

fun FeedFlowStrings.aiErrorMessage(exception: AiSummaryException): String = when (exception.error) {
    AiSummaryError.MISSING_API_KEY -> settingsAiSetKeyHint
    AiSummaryError.TIMEOUT -> aiErrorTimeout
    AiSummaryError.NETWORK -> networkErrorMessage(exception.detail)
    AiSummaryError.HTTP -> aiErrorProvider(exception.detail.orEmpty())
    AiSummaryError.PARSE -> aiErrorParse
    AiSummaryError.BLOCKED -> aiErrorBlocked
    AiSummaryError.SAFETY -> aiErrorSafety
    AiSummaryError.TOKEN_LIMIT -> aiErrorTokenLimit
    AiSummaryError.EMPTY -> aiErrorEmpty
}

// Everything the transport throws that is not a timeout lands here, so a bare "check your
// connection" is wrong as often as it is right: a blocked host, a TLS failure and a bug in the
// request all read the same. The exception detail is the only thing that separates them.
private fun FeedFlowStrings.networkErrorMessage(detail: String?): String {
    val text = detail?.takeIf { it.isNotBlank() } ?: return aiErrorNetwork
    return aiErrorNetworkDetail(text)
}
