package com.prof18.feedflow.core.model

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
const val DEFAULT_AI_MODEL = "gemini-3.5-flash-lite"

/** How much of a single article a summary is allowed to consider. */
const val MAX_ARTICLE_TEXT_LENGTH = 10000

/**
 * Hard cap on what any one request may carry, whatever the caller built it from. A separate
 * concern from [MAX_ARTICLE_TEXT_LENGTH]: batched callers pack many short inputs into one
 * request and need to know where the request itself stops, not where an article does.
 */
const val MAX_REQUEST_INPUT_LENGTH = 10000

private const val API_KEY_HEADER = "x-goog-api-key"
private const val GENERATE_CONTENT_METHOD = ":generateContent"
private const val JSON_MIME_TYPE = "application/json"
private val VALID_MODEL_NAME = Regex("^[A-Za-z0-9._-]+$")

// Every finish reason that means "the model stopped because something objected", as opposed to
// running out of room. RECITATION and SPII land here too: the user sees a refusal either way.
private val SAFETY_FINISH_REASONS = setOf(
    "SAFETY",
    "PROHIBITED_CONTENT",
    "BLOCKLIST",
    "RECITATION",
    "SPII",
)
private const val FINISH_REASON_MAX_TOKENS = "MAX_TOKENS"

class GeminiArticleAiService(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val configProvider: suspend () -> AiConfig,
) : ArticleAiService {

    override suspend fun complete(systemPrompt: String, input: String, responseSchema: String?): String {
        val config = configProvider()
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiSummaryException(AiSummaryError.MISSING_API_KEY)

        // Path-segment only: free-text model names with ?, /, spaces, or newlines produce a
        // malformed generateContent URL and a confusing network error instead of a clear fallback.
        val model = config.model.trim()
            .takeIf { it.matches(VALID_MODEL_NAME) }
            ?: DEFAULT_AI_MODEL
        val url = "$GEMINI_MODELS_URL$model$GENERATE_CONTENT_METHOD"
        val (status, body) = request(url, apiKey, systemPrompt, input, responseSchema)
        if (!status.isSuccess()) {
            throw httpFailure(status, body)
        }
        return extractText(body)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun request(
        url: String,
        apiKey: String,
        systemPrompt: String,
        input: String,
        responseSchema: String?,
    ): Pair<HttpStatusCode, String> {
        // Neither thinkingConfig nor maxOutputTokens is ever sent, and both omissions are
        // measured rather than assumed. Flash-lite spends no thinking tokens by default, but
        // asking for thinkingLevel "low" turns thinking on and costs 2.4x the tokens for the
        // same answer, and the level is rejected outright by models older than 3.x. Meanwhile
        // maxOutputTokens caps thinking and answer together, so any value low enough to be a
        // useful guard can return a truncated answer after billing for the whole budget.
        val requestBody = GeminiRequest(
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = input.take(MAX_REQUEST_INPUT_LENGTH))))),
            generationConfig = responseSchema?.let {
                GeminiGenerationConfig(
                    responseMimeType = JSON_MIME_TYPE,
                    responseSchema = json.parseToJsonElement(it),
                )
            },
        )

        return try {
            val response = httpClient.post(url) {
                header(API_KEY_HEADER, apiKey)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GeminiRequest.serializer(), requestBody))
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
            // The type as well as the message: everything the transport throws that is not a
            // timeout is reported as one error, and UnknownHostException carries only a hostname
            // while NetworkOnMainThreadException carries nothing at all. Without the type, a
            // blocked host and a bug in the request are the same string on screen.
            detail = listOfNotNull(e::class.simpleName, e.message?.takeIf { it.isNotBlank() })
                .joinToString(": "),
            cause = e,
        )
    }

    private fun httpFailure(status: HttpStatusCode, body: String): AiSummaryException {
        val message = runCatching {
            json.decodeFromString(GeminiResponse.serializer(), body).error?.message
        }.getOrNull()
        val detail = message ?: body.takeIf { it.isNotBlank() } ?: status.description
        return AiSummaryException(AiSummaryError.HTTP, "${status.value}: $detail")
    }

    private fun extractText(body: String): String {
        val response = decode(body)
        val candidate = response.candidates?.firstOrNull()

        // Read the text before judging the finish reason: a truncated answer still tells the user
        // more than an error does, and only a genuinely empty one is worth failing on.
        val text = candidate?.content?.parts.orEmpty().mapNotNull { it.text }.joinToString("")
        if (text.isNotBlank()) {
            return text
        }
        throw emptyReplyFailure(response, candidate)
    }

    private fun decode(body: String): GeminiResponse = runCatching {
        json.decodeFromString(GeminiResponse.serializer(), body)
    }.getOrElse { throw AiSummaryException(AiSummaryError.PARSE, it.message, it) }

    private fun emptyReplyFailure(
        response: GeminiResponse,
        candidate: GeminiCandidate?,
    ): AiSummaryException {
        // A prompt the model refuses outright is reported here instead of as a candidate.
        val blockReason = response.promptFeedback?.blockReason
        return when {
            blockReason != null -> AiSummaryException(AiSummaryError.BLOCKED, blockReason)
            candidate == null -> AiSummaryException(AiSummaryError.EMPTY)
            candidate.finishReason in SAFETY_FINISH_REASONS -> AiSummaryException(AiSummaryError.SAFETY)
            candidate.finishReason == FINISH_REASON_MAX_TOKENS -> AiSummaryException(AiSummaryError.TOKEN_LIMIT)
            else -> AiSummaryException(AiSummaryError.EMPTY)
        }
    }
}

@Serializable
internal data class GeminiRequest(
    @SerialName("system_instruction")
    val systemInstruction: GeminiContent,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart>? = null,
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
)

@Serializable
internal data class GeminiGenerationConfig(
    val responseMimeType: String,
    val responseSchema: JsonElement,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: GeminiPromptFeedback? = null,
    val error: GeminiError? = null,
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

@Serializable
internal data class GeminiPromptFeedback(
    val blockReason: String? = null,
)

@Serializable
internal data class GeminiError(
    val message: String? = null,
    val status: String? = null,
)
