package com.prof18.feedflow.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArticleSummaryRepositoryTest {

    @Test
    fun `htmlToPlainText strips tags and collapses whitespace`() {
        val html = "<h2>Title</h2>\n<p>First <b>bold</b> line.</p>\n\n<p>Second   line.</p>"
        assertEquals("Title First bold line. Second line.", htmlToPlainText(html))
    }

    @Test
    fun `contentHash is stable for the same fields`() {
        assertEquals(contentHash("model", "prompt", "text"), contentHash("model", "prompt", "text"))
    }

    @Test
    fun `contentHash changes when the model or the prompt changes`() {
        val base = contentHash("model", "prompt", "text")
        assertNotEquals(base, contentHash("other-model", "prompt", "text"))
        assertNotEquals(base, contentHash("model", "other-prompt", "text"))
        assertNotEquals(base, contentHash("model", "prompt", "other-text"))
    }

    @Test
    fun `contentHash separates fields so concatenation does not collide`() {
        assertNotEquals(contentHash("ab", "c"), contentHash("a", "bc"))
    }

    @Test
    fun `contentHash is url safe hex`() {
        assertTrue(contentHash("model", "prompt", "text").all { it in "0123456789abcdef" })
    }
}
