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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Scores are only useful relative to each other, so the whole batch goes in one request and the
// model is given an absolute rubric to keep separate batches roughly comparable.
internal val RELEVANCE_SYSTEM_PROMPT = """
    You rate how newsworthy each headline is. You are given a numbered list of articles.
    Reply with a JSON array of objects, one for every index you were given, in the form
    [{"i": <index>, "s": <score>}]. Score 0 to 100 using this rubric:
    90-100 major breaking news with wide consequence.
    70-89 significant national, international or industry news.
    40-69 routine reporting, incremental updates, opinion.
    10-39 minor items, listicles, deals, roundups.
    0-9 promotional, trivial or purely personal content.
    Judge the article, not the writing style. Return no prose and no markdown.
""".trimIndent().replace("\n", " ")

class ArticleRelevanceRepository(
    private val databaseHelper: DatabaseHelper,
    private val articleAiService: ArticleAiService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val feedAppearanceSettingsRepository: FeedAppearanceSettingsRepository,
    private val logger: Logger,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val scoringMutex = Mutex()
    private val mutableIsRanking = MutableStateFlow(false)

    /** True only while a scoring run is genuinely in flight. */
    val isRanking: StateFlow<Boolean> = mutableIsRanking.asStateFlow()

    /**
     * No-op unless the user is actually sorting by relevance and has a key configured, so the
     * default chronological timeline never spends a request.
     *
     * Scoped to [feedFilter] so the budget goes on the list currently on screen rather than on
     * feeds the user is not reading.
     *
     * @return true when at least one score was written, meaning the caller should re-publish.
     */
    suspend fun scoreIfNeeded(feedFilter: FeedFilter, showReadItems: Boolean): Boolean {
        if (!aiSettingsRepository.isAiEnabled.value) return false
        if (feedAppearanceSettingsRepository.getFeedOrder() != FeedOrder.MOST_RELEVANT) return false
        // Checked before the key so the Keystore is not touched at all while AI is off.
        if (aiSettingsRepository.getApiKey().isNullOrBlank()) return false
        // A refresh, a feed switch and a sort change can all land within a second of each other,
        // and each would otherwise spend a full run of requests re-scoring the same headlines.
        if (!scoringMutex.tryLock()) return false

        // Raised only once the early returns are behind us: on date sorting this method does
        // nothing, and a flag set before them makes the progress banner flash on every refresh.
        mutableIsRanking.update { true }
        return try {
            scoreUnscoredItems(feedFilter, showReadItems)
        } finally {
            mutableIsRanking.update { false }
            scoringMutex.unlock()
        }
    }

    private suspend fun scoreUnscoredItems(feedFilter: FeedFilter, showReadItems: Boolean): Boolean {
        discardScoresFromAnotherModel()

        var wroteAny = false
        val items = databaseHelper.getUnscoredItems(
            feedFilter = feedFilter,
            showReadItems = showReadItems,
            limit = MAX_ITEMS_PER_RUN,
        )
        for (batch in batchesUnderPromptBudget(items)) {
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
        val prompt = batch.mapIndexed(::promptLine).joinToString("\n")

        val reply = articleAiService.complete(
            systemPrompt = RELEVANCE_SYSTEM_PROMPT,
            input = prompt,
        )
        return parseScores(reply, batch, json)
    }

    // The model has no way to know the previous model's scale, so mixing them would sort
    // articles against numbers that mean different things.
    private suspend fun discardScoresFromAnotherModel() {
        val signature = "${aiSettingsRepository.getModel()}|$RUBRIC_VERSION"
        if (aiSettingsRepository.getRelevanceSignature() == signature) return
        databaseHelper.clearRelevanceScores()
        aiSettingsRepository.setRelevanceSignature(signature)
    }

    private companion object {
        const val MAX_ITEMS_PER_RUN = 200L

        // Bump when RELEVANCE_SYSTEM_PROMPT changes in a way that shifts the scale.
        const val RUBRIC_VERSION = 1
    }
}

private const val MAX_BATCH_SIZE = 50
private const val TITLE_LIMIT = 200
private const val SUBTITLE_LIMIT = 300

internal fun promptLine(index: Int, item: UnscoredItem): String {
    val subtitle = item.subtitle?.take(SUBTITLE_LIMIT).orEmpty()
    return "$index. ${item.title.take(TITLE_LIMIT)}${if (subtitle.isBlank()) "" else " - $subtitle"}"
}

/**
 * The service truncates its input at [MAX_REQUEST_INPUT_LENGTH], so a batch has to be closed on
 * the character budget as well as the item count. Fifty headlines with subtitles run to roughly
 * 25k characters: left to a fixed chunk size, everything past the cut reaches the model as
 * nothing at all, comes back unscored, and is paid for again on the next run.
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
        // Re-measured after a flush: the index is part of the line, so a line's cost depends on
        // which batch it lands in.
        length += promptLine(current.size, item).length + 1
        current += item
    }
    if (current.isNotEmpty()) {
        batches += current
    }
    return batches
}

/**
 * Maps the model's reply back onto the batch. Entries are read one at a time so a single
 * malformed row costs one score instead of the whole batch, and anything unrecognised is
 * dropped rather than guessed at: an unscored article sorts as average, a wrongly scored one
 * sorts wrongly until the model changes.
 */
internal fun parseScores(reply: String, batch: List<UnscoredItem>, json: Json): Map<String, Int> {
    val entries = runCatching {
        json.parseToJsonElement(reply.unwrapJsonArray()).jsonArray
    }.getOrElse { return emptyMap() }

    return entries.mapNotNull { element ->
        val fields = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val index = fields["i"]?.intOrNull() ?: return@mapNotNull null
        val score = fields["s"]?.intOrNull() ?: return@mapNotNull null
        val item = batch.getOrNull(index) ?: return@mapNotNull null
        item.id to score.coerceIn(MIN_RELEVANCE_SCORE, MAX_RELEVANCE_SCORE)
    }.toMap()
}

private fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
    runCatching { jsonPrimitive.intOrNull }.getOrNull()

// Providers still wrap JSON in a markdown fence often enough to be worth handling.
private fun String.unwrapJsonArray(): String {
    val start = indexOf('[')
    val end = lastIndexOf(']')
    return if (start >= 0 && end > start) substring(start, end + 1) else this
}
