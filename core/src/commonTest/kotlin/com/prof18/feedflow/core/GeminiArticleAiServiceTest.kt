package com.prof18.feedflow.core

import com.prof18.feedflow.core.model.AiConfig
import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.core.model.DEFAULT_AI_MODEL
import com.prof18.feedflow.core.model.GEMINI_MODELS_URL
import com.prof18.feedflow.core.model.GeminiArticleAiService
import com.prof18.feedflow.core.model.MAX_REQUEST_INPUT_LENGTH
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiArticleAiServiceTest {

    private fun config(apiKey: String?, model: String = DEFAULT_AI_MODEL) =
        AiConfig(apiKey = apiKey, model = model)

    private fun MockRequestHandleScope.jsonResponse(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun textCandidate(text: String, finishReason: String = "STOP") =
        """{"candidates":[{"content":{"parts":[{"text":"$text"}],"role":"model"},"finishReason":"$finishReason"}]}"""

    @Test
    fun `complete returns the candidate text and authenticates with the api key header`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("test-api-key", request.headers["x-goog-api-key"])
            // Never as a bearer token: the native endpoint ignores Authorization entirely.
            assertEquals(null, request.headers[HttpHeaders.Authorization])
            jsonResponse(textCandidate("This is a summary."))
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "test-api-key") }

        assertEquals("This is a summary.", service.complete("System prompt", "Article text"))
    }

    @Test
    fun `complete posts to the generateContent url for the configured model`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            jsonResponse(textCandidate("ok"))
        }

        GeminiArticleAiService(HttpClient(mockEngine)) {
            config(apiKey = "key", model = "gemini-3.5-flash")
        }.complete("Prompt", "Text")

        assertEquals("${GEMINI_MODELS_URL}gemini-3.5-flash:generateContent", requestedUrls.single())
    }

    @Test
    fun `complete falls back to the default model when it is blank`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            jsonResponse(textCandidate("ok"))
        }

        GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key", model = "  ") }
            .complete("Prompt", "Text")

        assertEquals("$GEMINI_MODELS_URL$DEFAULT_AI_MODEL:generateContent", requestedUrls.single())
    }

    @Test
    fun `complete falls back to the default model when the name is not a path segment`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            jsonResponse(textCandidate("ok"))
        }

        GeminiArticleAiService(HttpClient(mockEngine)) {
            config(apiKey = "key", model = "gemini-3.5-flash-lite?foo=bar")
        }.complete("Prompt", "Text")

        assertEquals("$GEMINI_MODELS_URL$DEFAULT_AI_MODEL:generateContent", requestedUrls.single())
    }

    @Test
    fun `complete sends the prompt as a system instruction and truncates the input`() = runTest {
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            capturedBody = (request.body as TextContent).text
            jsonResponse(textCandidate("ok"))
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        service.complete("System prompt instruction", "a".repeat(MAX_REQUEST_INPUT_LENGTH + 500))

        assertTrue(capturedBody.contains("a".repeat(MAX_REQUEST_INPUT_LENGTH)))
        assertFalse(capturedBody.contains("a".repeat(MAX_REQUEST_INPUT_LENGTH + 1)))
        assertTrue(capturedBody.contains(""""system_instruction":{"parts":[{"text":"System prompt instruction"}]}"""))
    }

    /**
     * Both omissions are load-bearing and measured: `thinkingLevel` doubles the token bill for the
     * same answer and is rejected by models older than 3.x, and `maxOutputTokens` is spent on
     * thinking before the answer, so a low cap returns a truncated summary that was paid for.
     */
    @Test
    fun `complete sends no generation config when no schema is requested`() = runTest {
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            capturedBody = (request.body as TextContent).text
            jsonResponse(textCandidate("ok"))
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        service.complete("Prompt", "Text")

        assertFalse(capturedBody.contains("generationConfig"))
        assertFalse(capturedBody.contains("thinking"))
        assertFalse(capturedBody.contains("maxOutputTokens"))
    }

    @Test
    fun `complete asks for json mode when a response schema is given`() = runTest {
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            capturedBody = (request.body as TextContent).text
            jsonResponse(textCandidate("[]"))
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        service.complete("Prompt", "Text", responseSchema = """{"type":"ARRAY"}""")

        assertTrue(capturedBody.contains(""""responseMimeType":"application/json""""))
        assertTrue(capturedBody.contains(""""responseSchema":{"type":"ARRAY"}"""))
    }

    @Test
    fun `complete reports the provider message on an http failure`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""",
                HttpStatusCode.BadRequest,
            )
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "bad") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.HTTP, exception.error)
        assertEquals("400: API key not valid.", exception.detail)
    }

    @Test
    fun `complete reports a missing model as an http failure naming the model`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"error":{"code":404,"message":"models/nope is not found.","status":"NOT_FOUND"}}""",
                HttpStatusCode.NotFound,
            )
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key", model = "nope") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertTrue(exception.detail.orEmpty().contains("models/nope is not found"))
    }

    @Test
    fun `complete reports a refused prompt as blocked`() = runTest {
        val mockEngine = MockEngine { jsonResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.BLOCKED, exception.error)
        assertEquals("SAFETY", exception.detail)
    }

    @Test
    fun `complete maps every objecting finish reason to safety`() = runTest {
        listOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION", "SPII").forEach { reason ->
            val mockEngine = MockEngine {
                jsonResponse("""{"candidates":[{"content":{"parts":[]},"finishReason":"$reason"}]}""")
            }
            val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

            val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
            assertEquals(AiSummaryError.SAFETY, exception.error, "finishReason $reason")
        }
    }

    // Thinking is billed against the output budget, so MAX_TOKENS can arrive with no text at all.
    @Test
    fun `complete reports an empty max tokens candidate as a token limit`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse("""{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}]}""")
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.TOKEN_LIMIT, exception.error)
    }

    // A partial answer beats an error message the user cannot act on.
    @Test
    fun `complete keeps a truncated answer instead of failing on it`() = runTest {
        val mockEngine = MockEngine { jsonResponse(textCandidate("The bank kept rates", "MAX_TOKENS")) }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        assertEquals("The bank kept rates", service.complete("Prompt", "Text"))
    }

    @Test
    fun `complete joins every text part of a candidate`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse("""{"candidates":[{"content":{"parts":[{"text":"one "},{"text":"two"}]}}]}""")
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        assertEquals("one two", service.complete("Prompt", "Text"))
    }

    @Test
    fun `complete reports no candidates as empty`() = runTest {
        val mockEngine = MockEngine { jsonResponse("""{"candidates":[]}""") }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.EMPTY, exception.error)
    }

    @Test
    fun `complete reports an unreadable body as a parse failure`() = runTest {
        val mockEngine = MockEngine { jsonResponse("not json at all") }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.PARSE, exception.error)
    }

    @Test
    fun `complete reports a timeout separately from other network failures`() = runTest {
        val mockEngine = MockEngine { throw HttpRequestTimeoutException("https://example.com", 1000L) }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.TIMEOUT, exception.error)
    }

    // Every non-timeout transport failure is reported as one error, so without the type in the
    // detail the screen shows "Network error" for a blocked host and for a bug in the request
    // alike - and an exception carrying no message at all leaves nothing to go on.
    @Test
    fun `complete names the transport failure type even when it carries no message`() = runTest {
        val mockEngine = MockEngine { throw IllegalStateException() }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.NETWORK, exception.error)
        assertEquals("IllegalStateException", exception.detail)
    }

    @Test
    fun `complete keeps both the type and the message when the failure has one`() = runTest {
        val mockEngine = MockEngine { throw IllegalStateException("Unable to resolve host") }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals("IllegalStateException: Unable to resolve host", exception.detail)
    }

    @Test
    fun `complete makes no request at all when the api key is missing`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            respond("", HttpStatusCode.OK)
        }
        val service = GeminiArticleAiService(HttpClient(mockEngine)) { config(apiKey = null) }

        val exception = assertFailsWith<AiSummaryException> { service.complete("Prompt", "Text") }
        assertEquals(AiSummaryError.MISSING_API_KEY, exception.error)
        assertEquals(0, requestCount)
    }
}
