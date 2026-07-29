package com.prof18.feedflow.core.model

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Gemini's OpenAI-compatible endpoint rather than its native one. The default provider is
 * unchanged, but the same request shape now also reaches OpenAI, Anthropic, Groq, OpenRouter,
 * Ollama and anything else that speaks chat completions, so the endpoint field is worth editing.
 */
const val DEFAULT_AI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
const val DEFAULT_AI_MODEL = "gemini-3.5-flash-lite"

/** How much of a single article a summary is allowed to consider. */
const val MAX_ARTICLE_TEXT_LENGTH = 10000

/**
 * Hard cap on what any one request may carry, whatever the caller built it from. A separate
 * concern from [MAX_ARTICLE_TEXT_LENGTH]: batched callers pack many short inputs into one
 * request and need to know where the request itself stops, not where an article does.
 */
const val MAX_REQUEST_INPUT_LENGTH = 10000

private const val CHAT_COMPLETIONS_PATH = "chat/completions"
private const val CONTENT_FILTER_MARKER = "content_filter"
private const val FINISH_REASON_LENGTH = "length"
private const val ROLE_SYSTEM = "system"
private const val ROLE_USER = "user"

class OpenAiCompatibleArticleAiService(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val configProvider: suspend () -> AiConfig,
) : ArticleAiService {

    override suspend fun complete(systemPrompt: String, input: String): String {
        val config = configProvider()
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiSummaryException(AiSummaryError.MISSING_API_KEY)

        val url = chatCompletionsUrl(config)
        val (status, body) = request(url, config, apiKey, input, systemPrompt)
        if (!status.isSuccess()) {
            throw httpFailure(url, status, body)
        }
        return extractContent(body)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun request(
        url: String,
        config: AiConfig,
        apiKey: String,
        input: String,
        systemPrompt: String,
    ): Pair<HttpStatusCode, String> {
        val requestBody = OpenAiRequest(
            model = config.model.trim().ifBlank { DEFAULT_AI_MODEL },
            messages = listOf(
                OpenAiMessage(role = ROLE_SYSTEM, content = systemPrompt),
                OpenAiMessage(role = ROLE_USER, content = input.take(MAX_REQUEST_INPUT_LENGTH)),
            ),
        )

        return try {
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(OpenAiRequest.serializer(), requestBody))
            }
            response.status to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure(e)
        }
    }

    // Engines that do not surface HttpRequestTimeoutException still say so in the message.
    private fun transportFailure(e: Exception): AiSummaryException {
        val timedOut = e is HttpRequestTimeoutException ||
            e.message?.contains("timeout", ignoreCase = true) == true
        return AiSummaryException(
            error = if (timedOut) AiSummaryError.TIMEOUT else AiSummaryError.NETWORK,
            detail = e.message,
            cause = e,
        )
    }

    private fun httpFailure(url: String, status: HttpStatusCode, body: String): AiSummaryException {
        val apiError = runCatching {
            json.decodeFromString(OpenAiResponse.serializer(), body).error
        }.getOrNull()

        // A prompt the provider refuses to run is rejected as an error, so it never reaches a
        // finish_reason. Output cut short by a filter is the SAFETY case instead.
        if (apiError?.isContentFilter() == true) {
            return AiSummaryException(AiSummaryError.BLOCKED, apiError.message)
        }
        val detail = apiError?.message ?: body.takeIf { it.isNotBlank() } ?: status.description
        // The endpoint is user-editable, and the usual failure is that it points somewhere this
        // request does not exist. Naming the URL is the difference between "404" and a fix.
        return AiSummaryException(AiSummaryError.HTTP, "${status.value} from $url: $detail")
    }

    private fun extractContent(body: String): String {
        val response = runCatching {
            json.decodeFromString(OpenAiResponse.serializer(), body)
        }.getOrElse { throw AiSummaryException(AiSummaryError.PARSE, it.message, it) }

        responseFailure(response)?.let { throw it }
        return response.choices.orEmpty().first().message?.content.orEmpty()
    }

    private fun responseFailure(response: OpenAiResponse): AiSummaryException? {
        // Gateways such as OpenRouter answer 200 with an error object instead of a failure status.
        response.error?.let { error ->
            val failure = if (error.isContentFilter()) AiSummaryError.BLOCKED else AiSummaryError.HTTP
            return AiSummaryException(failure, error.message)
        }

        val choice = response.choices?.firstOrNull()
            ?: return AiSummaryException(AiSummaryError.EMPTY)

        return when {
            choice.finishReason == CONTENT_FILTER_MARKER -> AiSummaryException(AiSummaryError.SAFETY)
            choice.finishReason == FINISH_REASON_LENGTH -> AiSummaryException(AiSummaryError.TOKEN_LIMIT)
            choice.message?.content.isNullOrBlank() -> AiSummaryException(AiSummaryError.EMPTY)
            else -> null
        }
    }
}

/**
 * The endpoint is user-editable, so tolerate a missing or duplicated trailing slash and a URL
 * that already names the chat completions path, which is how most provider docs print it.
 */
internal fun chatCompletionsUrl(config: AiConfig): String {
    val base = config.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_AI_BASE_URL.trimEnd('/') }
    return if (base.endsWith(CHAT_COMPLETIONS_PATH)) base else "$base/$CHAT_COMPLETIONS_PATH"
}

@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
)

@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: String,
)

// Separate from the request message: a reply is only read for its content, and requiring the
// role back would fail the whole decode over a field nothing here uses.
@Serializable
internal data class OpenAiReplyMessage(
    val content: String? = null,
)

@Serializable
internal data class OpenAiResponse(
    val choices: List<OpenAiChoice>? = null,
    val error: OpenAiErrorDetails? = null,
)

@Serializable
internal data class OpenAiChoice(
    val message: OpenAiReplyMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAiErrorDetails(
    val message: String? = null,
    val type: String? = null,
    // Kept as raw JSON: OpenAI sends a string code, Gemini's compatibility layer sends a number,
    // and a typed field would fail the whole decode and lose the message with it.
    val code: JsonElement? = null,
) {
    fun isContentFilter(): Boolean =
        type?.contains(CONTENT_FILTER_MARKER) == true ||
            code?.toString()?.contains(CONTENT_FILTER_MARKER) == true
}
