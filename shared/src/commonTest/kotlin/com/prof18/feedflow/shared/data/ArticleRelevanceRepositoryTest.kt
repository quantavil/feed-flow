package com.prof18.feedflow.shared.data

import com.prof18.feedflow.core.model.MAX_REQUEST_INPUT_LENGTH
import com.prof18.feedflow.database.UnscoredItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleRelevanceRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val batch = listOf(
        UnscoredItem(id = "a", title = "First", subtitle = null),
        UnscoredItem(id = "b", title = "Second", subtitle = "sub"),
        UnscoredItem(id = "c", title = "Third", subtitle = null),
    )

    private fun parse(reply: String) = parseScores(reply, batch, json)

    @Test
    fun `maps indices back onto the batch`() {
        val scores = parse("""[{"i":0,"s":90},{"i":1,"s":20},{"i":2,"s":55}]""")
        assertEquals(mapOf("a" to 90, "b" to 20, "c" to 55), scores)
    }

    @Test
    fun `keeps the entries it understands when some are missing`() {
        val scores = parse("""[{"i":2,"s":70}]""")
        assertEquals(mapOf("c" to 70), scores)
    }

    @Test
    fun `ignores indices that were never sent`() {
        // The model occasionally invents rows; scoring the wrong article is worse than not scoring.
        val scores = parse("""[{"i":0,"s":80},{"i":99,"s":80},{"i":-1,"s":80}]""")
        assertEquals(mapOf("a" to 80), scores)
    }

    @Test
    fun `clamps scores outside the rubric range`() {
        val scores = parse("""[{"i":0,"s":420},{"i":1,"s":-30}]""")
        assertEquals(mapOf("a" to 100, "b" to 0), scores)
    }

    @Test
    fun `unwraps a markdown fenced reply`() {
        val scores = parse("```json\n[{\"i\":0,\"s\":65}]\n```")
        assertEquals(mapOf("a" to 65), scores)
    }

    @Test
    fun `returns nothing for a reply that is not json`() {
        assertTrue(parse("I cannot rate these headlines.").isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("""[{"i":0,"s":"high"}]""").isEmpty())
    }

    @Test
    fun `batches stay under the length the service will truncate at`() {
        val items = List(200) {
            UnscoredItem(id = "id$it", title = "t".repeat(200), subtitle = "s".repeat(300))
        }

        val batches = batchesUnderPromptBudget(items)

        assertEquals(items, batches.flatten())
        batches.forEach { batch ->
            val prompt = batch.mapIndexed(::promptLine).joinToString("\n")
            assertTrue(prompt.length <= MAX_REQUEST_INPUT_LENGTH, "batch of ${prompt.length} chars")
        }
    }

    @Test
    fun `short headlines still batch by item count`() {
        val items = List(120) { UnscoredItem(id = "id$it", title = "Short", subtitle = null) }

        assertEquals(listOf(50, 50, 20), batchesUnderPromptBudget(items).map { it.size })
    }

    @Test
    fun `rubric prompt names every band so separate batches stay comparable`() {
        listOf("90-100", "70-89", "40-69", "10-39", "0-9").forEach { band ->
            assertTrue(RELEVANCE_SYSTEM_PROMPT.contains(band), "missing band $band")
        }
    }

    // The schema is what makes a reply parseScores cannot read impossible, so a typo in it would
    // quietly hand that job back to the prompt and reintroduce the unscored-and-rebilled loop.
    @Test
    fun `response schema describes the array of index and score objects the parser expects`() {
        val schema = json.parseToJsonElement(RELEVANCE_RESPONSE_SCHEMA).jsonObject

        assertEquals("ARRAY", schema.getValue("type").jsonPrimitive.content)
        val item = schema.getValue("items").jsonObject
        assertEquals("OBJECT", item.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("i", "s"), item.getValue("properties").jsonObject.keys)
        item.getValue("properties").jsonObject.values.forEach {
            assertEquals("INTEGER", it.jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertEquals(
            listOf("i", "s"),
            item.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
