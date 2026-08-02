package com.prof18.feedflow.shared.data

import com.prof18.feedflow.core.model.ArticleAiService
import com.prof18.feedflow.core.model.MAX_ARTICLE_TEXT_LENGTH
import com.prof18.feedflow.database.DatabaseHelper

class ArticleSummaryRepository(
    private val databaseHelper: DatabaseHelper,
    private val articleAiService: ArticleAiService,
    private val aiSettingsRepository: AiSettingsRepository,
) {
    suspend fun summarise(articleId: String, articleHtml: String): String {
        val model = aiSettingsRepository.getModel()
        val systemPrompt = aiSettingsRepository.getSystemPrompt()
        // Truncated here rather than in the service: this is the article-length limit, and it has
        // to be applied before the hash or the cache key would not describe what was sent.
        val articleText = htmlToPlainText(articleHtml).take(MAX_ARTICLE_TEXT_LENGTH)
        // Model and prompt are both part of the key, so changing either produces a fresh summary
        // instead of replaying one the previous settings wrote.
        val contentHash = contentHash(model, systemPrompt, articleText)

        databaseHelper.getArticleSummary(contentHash)?.let { return it }

        val summary = articleAiService.complete(systemPrompt = systemPrompt, input = articleText)
        databaseHelper.insertArticleSummary(articleId, contentHash, summary)
        return summary
    }
}

// Script and style bodies sit between tags, so stripping tags alone leaves their source behind as
// "article text": the model is then billed for minified JavaScript and summarises around it.
private val scriptOrStyleRegex = Regex(
    "<(script|style)\\b[^>]*>.*?</\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val htmlTagRegex = Regex("<[^>]*>")
private val whitespaceRegex = Regex("\\s+")
private val numericEntityRegex = Regex("&#(x?)([0-9a-fA-F]+);")

// The named few that actually survive tag stripping; anything rarer is left alone rather than
// shipping an entity table for a summariser input. Numeric forms such as &#39; need no entry:
// numericEntityRegex has already decoded them by the time this map is applied.
private val namedEntities = mapOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&nbsp;" to " ",
)

internal fun htmlToPlainText(html: String): String {
    var text = html.replace(scriptOrStyleRegex, " ").replace(htmlTagRegex, " ")
    text = numericEntityRegex.replace(text) { match ->
        val radix = if (match.groupValues[1].isEmpty()) DECIMAL_RADIX else HEX_RADIX
        match.groupValues[2].toIntOrNull(radix)?.toChar()?.toString() ?: match.value
    }
    for ((entity, replacement) in namedEntities) {
        text = text.replace(entity, replacement)
    }
    // Ampersand last, so "&amp;lt;" does not decode twice into a tag character.
    return text.replace("&amp;", "&").replace(whitespaceRegex, " ").trim()
}

private const val DECIMAL_RADIX = 10

private const val FNV_OFFSET_BASIS = 14695981039346656037uL
private const val FNV_PRIME = 1099511628211uL
private const val FIELD_SEPARATOR = 0uL
private const val HEX_RADIX = 16

// FNV-1a 64. This is a cache key, not a security primitive: a collision costs one stale
// summary, which is why it beats pulling a SHA-256 implementation into common code.
internal fun contentHash(vararg fields: String): String {
    var hash = FNV_OFFSET_BASIS
    for (field in fields) {
        for (char in field) {
            hash = (hash xor char.code.toULong()) * FNV_PRIME
        }
        hash = (hash xor FIELD_SEPARATOR) * FNV_PRIME
    }
    return hash.toString(HEX_RADIX)
}
