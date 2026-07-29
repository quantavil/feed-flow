package com.prof18.feedflow.core.model

interface ArticleAiService {
    /**
     * One-shot completion. Callers own their prompt and their parsing.
     *
     * [responseSchema] is a raw Gemini `responseSchema` object. Passing one switches the model
     * into JSON mode, which makes a malformed reply structurally impossible rather than something
     * the caller has to defend against.
     */
    suspend fun complete(systemPrompt: String, input: String, responseSchema: String? = null): String
}

enum class AiSummaryError {
    MISSING_API_KEY,
    TIMEOUT,
    NETWORK,
    HTTP,
    PARSE,
    BLOCKED,
    SAFETY,
    TOKEN_LIMIT,
    EMPTY,
}

// `detail` carries provider text (HTTP status, API error body) that is not ours to translate.
class AiSummaryException(
    val error: AiSummaryError,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: error.name, cause)

data class AiConfig(
    val apiKey: String?,
    val model: String,
)
