package com.prof18.feedflow.core

import com.prof18.feedflow.core.model.AiConfig
import com.prof18.feedflow.core.model.AiSummaryError
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.core.model.DEFAULT_AI_BASE_URL
import com.prof18.feedflow.core.model.DEFAULT_AI_MODEL
import com.prof18.feedflow.core.model.MAX_REQUEST_INPUT_LENGTH
import com.prof18.feedflow.core.model.OpenAiCompatibleArticleAiService
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

class OpenAiCompatibleArticleAiServiceTest {

    private fun config(
        apiKey: String?,
        model: String = DEFAULT_AI_MODEL,
        baseUrl: String = DEFAULT_AI_BASE_URL,
    ) = AiConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)

    private fun MockRequestHandleScope.jsonResponse(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `complete returns the message content on 200 OK response`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("Bearer test-api-key", request.headers[HttpHeaders.Authorization])
            jsonResponse(
                """
                {
                  "choices": [
                    {
                      "message": {"role": "assistant", "content": "This is a summary.\nKey points:\n- Point 1"},
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "test-api-key") }

        val result = service.complete("System prompt instruction", "Article text content")
        assertEquals("This is a summary.\nKey points:\n- Point 1", result)
    }

    @Test
    fun `complete throws exception on non-200 HTTP response`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"error": {"message": "Invalid API key.", "type": "invalid_request_error"}}""",
                HttpStatusCode.Forbidden,
            )
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "invalid-key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.HTTP, exception.error)
        assertTrue(exception.detail.orEmpty().startsWith("403 from "))
        assertTrue(exception.detail.orEmpty().endsWith(": Invalid API key."))
    }

    // Gemini's compatibility layer answers with a numeric error code where OpenAI sends a string.
    @Test
    fun `complete reports the provider message when the error code is a number`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"error": {"code": 400, "message": "API key not valid.", "status": "INVALID_ARGUMENT"}}""",
                HttpStatusCode.BadRequest,
            )
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertTrue(exception.detail.orEmpty().endsWith(": API key not valid."))
    }

    @Test
    fun `complete throws exception on empty choices list`() = runTest {
        val mockEngine = MockEngine { jsonResponse("""{"choices": []}""") }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.EMPTY, exception.error)
    }

    @Test
    fun `complete throws exception when the prompt is refused by a content filter`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"error": {"message": "Filtered.", "code": "content_filter"}}""",
                HttpStatusCode.BadRequest,
            )
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.BLOCKED, exception.error)
    }

    // Gateways such as OpenRouter answer 200 with an error object instead of a failure status.
    @Test
    fun `complete throws exception on an error object inside a 200 response`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse("""{"error": {"message": "No endpoints found.", "type": "invalid_request_error"}}""")
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.HTTP, exception.error)
        assertEquals("No endpoints found.", exception.detail)
    }

    @Test
    fun `complete throws exception on content_filter finish reason`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse(
                """{"choices": [{"message": {"content": "unsafe"}, "finish_reason": "content_filter"}]}""",
            )
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.SAFETY, exception.error)
    }

    @Test
    fun `complete throws exception on length finish reason`() = runTest {
        val mockEngine = MockEngine {
            jsonResponse("""{"choices": [{"message": {"content": "truncated"}, "finish_reason": "length"}]}""")
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.TOKEN_LIMIT, exception.error)
    }

    @Test
    fun `complete throws exception on network timeout`() = runTest {
        val mockEngine = MockEngine {
            throw HttpRequestTimeoutException("https://example.com", 1000L)
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.TIMEOUT, exception.error)
    }

    @Test
    fun `complete throws exception when api key is missing and makes no request`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            respond("", HttpStatusCode.OK)
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = null) }

        val exception = assertFailsWith<AiSummaryException> {
            service.complete("Prompt", "Text")
        }
        assertEquals(AiSummaryError.MISSING_API_KEY, exception.error)
        assertEquals(0, requestCount)
    }

    @Test
    fun `complete truncates article text and sends the prompt as a system message`() = runTest {
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            capturedBody = (request.body as TextContent).text
            jsonResponse("""{"choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}]}""")
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        val longArticle = "a".repeat(MAX_REQUEST_INPUT_LENGTH + 500)
        service.complete("System prompt instruction", longArticle)

        val sentArticleText = "a".repeat(MAX_REQUEST_INPUT_LENGTH)
        assertTrue(capturedBody.contains(sentArticleText))
        assertFalse(capturedBody.contains("a".repeat(MAX_REQUEST_INPUT_LENGTH + 1)))
        // The prompt must travel as its own system message, never concatenated into the article.
        assertTrue(capturedBody.contains("""{"role":"system","content":"System prompt instruction"}"""))
        assertFalse(capturedBody.contains("System prompt instructiona"))
    }

    @Test
    fun `complete sends the configured model in the body and posts to the configured endpoint`() = runTest {
        val requestedUrls = mutableListOf<String>()
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            capturedBody = (request.body as TextContent).text
            jsonResponse("""{"choices": [{"message": {"content": "ok"}}]}""")
        }

        OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) {
            config(apiKey = "key", model = "llama-3.3-70b", baseUrl = "https://api.groq.com/openai/v1")
        }.complete("Prompt", "Text")

        // A base URL typed without the trailing slash must not collapse into the host root.
        assertEquals("https://api.groq.com/openai/v1/chat/completions", requestedUrls.single())
        assertTrue(capturedBody.contains(""""model":"llama-3.3-70b""""))
    }

    @Test
    fun `complete does not repeat the chat completions path when the endpoint already names it`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            jsonResponse("""{"choices": [{"message": {"content": "ok"}}]}""")
        }

        OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) {
            config(apiKey = "key", baseUrl = "https://openrouter.ai/api/v1/chat/completions")
        }.complete("Prompt", "Text")

        assertEquals("https://openrouter.ai/api/v1/chat/completions", requestedUrls.single())
    }

    @Test
    fun `complete falls back to the defaults when model and base url are blank`() = runTest {
        val requestedUrls = mutableListOf<String>()
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            capturedBody = (request.body as TextContent).text
            jsonResponse("""{"choices": [{"message": {"content": "ok"}}]}""")
        }

        OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) {
            config(apiKey = "key", model = "  ", baseUrl = "")
        }.complete("Prompt", "Text")

        assertEquals(DEFAULT_AI_BASE_URL + "chat/completions", requestedUrls.single())
        assertTrue(capturedBody.contains(""""model":"$DEFAULT_AI_MODEL""""))
    }

    // response_format is deliberately never sent: Anthropic and Ollama reject or ignore it, and
    // json_object would force an object where the relevance prompt asks for an array.
    @Test
    fun `complete sends only model and messages`() = runTest {
        var capturedBody = ""
        val mockEngine = MockEngine { request ->
            capturedBody = (request.body as TextContent).text
            jsonResponse("""{"choices": [{"message": {"content": "ok"}}]}""")
        }
        val service = OpenAiCompatibleArticleAiService(HttpClient(mockEngine)) { config(apiKey = "key") }

        service.complete("Prompt", "Text")

        assertFalse(capturedBody.contains("response_format"))
        assertEquals(
            """{"model":"$DEFAULT_AI_MODEL","messages":[{"role":"system","content":"Prompt"},""" +
                """{"role":"user","content":"Text"}]}""",
            capturedBody,
        )
    }
}
