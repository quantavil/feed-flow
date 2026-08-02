package com.prof18.feedflow.shared.data

import co.touchlab.kermit.Logger
import com.prof18.feedflow.core.model.AiSummaryException
import com.prof18.feedflow.core.model.ArticleAiService
import com.prof18.feedflow.core.model.FeedFilter
import com.prof18.feedflow.core.model.FeedOrder
import com.prof18.feedflow.core.model.MAX_RELEVANCE_SCORE
import com.prof18.feedflow.core.model.MAX_REQUEST_INPUT_LENGTH
import com.prof18.feedflow.core.model.MIN_RELEVANCE_SCORE
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.database.UnscoredItem
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Scores are only useful relative to each other, so the whole batch goes in one request and the
// model is given an absolute rubric to keep separate batches roughly comparable.
internal val RELEVANCE_SYSTEM_PROMPT = """
    You rate how newsworthy each headline is. You are given a numbered list of articles.
    Reply with one object for every index you were given, where "i" is the index and "s" is the
    score. Score 0 to 100 using this rubric:
    90-100 major breaking news with wide consequence.
    70-89 significant national, international or industry news.
    40-69 routine reporting, incremental updates, opinion.
    10-39 minor items, listicles, deals, roundups.
    0-9 promotional, trivial or purely personal content.
    Judge the article, not the writing style.
""".trimIndent().replace("\n", " ")

// Sent as the model's response schema, so a reply that is not this shape is impossible rather
// than something to recover from. Without it a prose refusal parses to zero scores, leaves every
// item unscored, and is paid for again on the next refresh.
internal const val RELEVANCE_RESPONSE_SCHEMA = """
    {"type":"ARRAY","items":{"type":"OBJECT",
    "properties":{"i":{"type":"INTEGER"},"s":{"type":"INTEGER"}},
    "required":["i","s"]}}
"""

class ArticleRelevanceRepository(
    private val databaseHelper: DatabaseHelper,
    private val articleAiService: ArticleAiService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val feedAppearanceSettingsRepository: FeedAppearanceSettingsRepository,
    private val logger: Logger,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * No-op unless the user is actually sorting by relevance and has a key configured, so the
     * default chronological timeline never spends a request.
     *
     * Scores everything unscored in [feedFilter], however long that takes: the run is paced to
     * stay inside the free tier's request budget and the caller cancels it when the user moves on.
     *
     * @return true when at least one score was written, meaning the caller should re-publish.
     */
    suspend fun scoreIfNeeded(feedFilter: FeedFilter, showReadItems: Boolean): Boolean {
        if (!aiSettingsRepository.isAiEnabled.value) return false
        if (feedAppearanceSettingsRepository.getFeedOrder() != FeedOrder.MOST_RELEVANT) return false
        // Checked before the key so the Keystore is not touched at all while AI is off.
        if (aiSettingsRepository.getApiKey().isNullOrBlank()) return false

        // Counts as a change on its own: the visible list and the pagination cursor are still
        // ordered by scores that no longer exist, so the caller has to re-publish even if every
        // request below then fails.
        var wroteAny = discardScoresFromAnotherModel()
        val items = databaseHelper.getUnscoredItems(feedFilter, showReadItems)
        for ((index, batch) in batchesUnderPromptBudget(items).withIndex()) {
            // Gemini's free tier allows 15 requests a minute and one 429 aborts the whole run
            // below, leaving everything after it unscored and paid for again next refresh.
            if (index > 0) {
                delay(BATCH_INTERVAL_MILLIS)
            }
            val scores = try {
                scoreBatch(batch)
            } catch (e: AiSummaryException) {
                // One failure means the next batch would fail the same way. Leave the rest
                // unscored; they sort as average and the next sync retries them.
                logger.w(e) { "Relevance scoring stopped after ${e.error}" }
                return wroteAny
            }
            if (scores.isNotEmpty()) {
                databaseHelper.updateRelevanceScores(scores)
                wroteAny = true
            }
        }
        return wroteAny
    }

    private suspend fun scoreBatch(batch: List<UnscoredItem>): Map<String, Int> {
        val reply = articleAiService.complete(
            systemPrompt = RELEVANCE_SYSTEM_PROMPT,
            input = batch.mapIndexed(::promptLine).joinToString("\n"),
            responseSchema = RELEVANCE_RESPONSE_SCHEMA,
        )
        return parseScores(reply, batch, json)
    }

    /**
     * The model has no way to know the previous model's scale, so mixing them would sort articles
     * against numbers that mean different things.
     *
     * @return true when scores were actually discarded.
     */
    private suspend fun discardScoresFromAnotherModel(): Boolean {
        val signature = "${aiSettingsRepository.getModel()}|$RUBRIC_VERSION"
        if (aiSettingsRepository.getRelevanceSignature() == signature) return false
        databaseHelper.clearRelevanceScores()
        aiSettingsRepository.setRelevanceSignature(signature)
        return true
    }

    private companion object {
        // 15 requests a minute is the free-tier ceiling, so a batch every four seconds is the
        // fastest a run may go.
        const val BATCH_INTERVAL_MILLIS = 4000L

        // Bump when RELEVANCE_SYSTEM_PROMPT changes in a way that shifts the scale.
        // 2: moved to the native Gemini endpoint with a response schema.
        const val RUBRIC_VERSION = 2
    }
}

private const val MAX_BATCH_SIZE = 40
private const val TITLE_LIMIT = 200
private const val SUBTITLE_LIMIT = 300

internal fun promptLine(index: Int, item: UnscoredItem): String {
    val subtitle = item.subtitle?.take(SUBTITLE_LIMIT).orEmpty()
    return "$index. ${item.title.take(TITLE_LIMIT)}${if (subtitle.isBlank()) "" else " - $subtitle"}"
}

/**
 * The service truncates its input at [MAX_REQUEST_INPUT_LENGTH], so a batch has to be closed on
 * the character budget as well as the item count, and in practice the budget is what binds:
 * [MAX_BATCH_SIZE] headlines with subtitles run well past the cut, and everything past it reaches
 * the model as nothing at all, comes back unscored, and is paid for again on the next run.
 */
internal fun batchesUnderPromptBudget(items: List<UnscoredItem>): List<List<UnscoredItem>> {
    val batches = mutableListOf<List<UnscoredItem>>()
    var current = mutableListOf<UnscoredItem>()
    var length = 0

    // +1 throughout for the newline the lines are joined with.
    for (item in items) {
        val wouldOverflow = length + promptLine(current.size, item).length + 1 > MAX_REQUEST_INPUT_LENGTH
        if (current.isNotEmpty() && (current.size >= MAX_BATCH_SIZE || wouldOverflow)) {
            batches += current
            current = mutableListOf()
            length = 0
        }
        // Measured after the flush, not before it: the index is part of the line, so the same
        // item costs fewer characters once it is first in a fresh batch.
        length += promptLine(current.size, item).length + 1
        current += item
    }
    if (current.isNotEmpty()) {
        batches += current
    }
    return batches
}

/**
 * Maps the model's reply back onto the batch. Rows are read one at a time so a single malformed
 * entry costs one score instead of the whole batch, and anything unrecognised is dropped rather
 * than guessed at: an unscored article sorts as average, a wrongly scored one sorts wrongly until
 * the model changes.
 */
internal fun parseScores(reply: String, batch: List<UnscoredItem>, json: Json): Map<String, Int> {
    val entries = runCatching {
        json.parseToJsonElement(reply.unwrapJsonArray()).jsonArray
    }.getOrElse { return emptyMap() }

    return entries.mapNotNull { element ->
        runCatching {
            val fields = element.jsonObject
            val item = batch[fields.getValue("i").jsonPrimitive.int]
            item.id to fields.getValue("s").jsonPrimitive.int
                .coerceIn(MIN_RELEVANCE_SCORE, MAX_RELEVANCE_SCORE)
        }.getOrNull()
    }.toMap()
}

// Providers still wrap JSON in a markdown fence often enough to be worth handling.
private fun String.unwrapJsonArray(): String {
    val start = indexOf('[')
    val end = lastIndexOf(']')
    return if (start >= 0 && end > start) substring(start, end + 1) else this
}
