package page.zepto.finto.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A question to the ledger, with the last few turns for context. */
@Serializable
data class AskRequest(
    val question: String,
    val history: List<AskTurnContext> = emptyList(),
)

@Serializable
data class AskTurnContext(val question: String, val answer: String)

/**
 * What an answer is built on. The figures come from ledger tools, never from
 * the model's arithmetic, and arrive with the filter that produced them.
 */
@Serializable
data class QueryResult(
    val ok: Boolean = true,
    val error: String? = null,
    val question: String = "",
    val answer: String? = null,
    val model: String? = null,
    val cached: Boolean = false,
    val groupBy: String? = null,
    val totals: List<TotalRow>? = null,
    val rows: List<SummaryRow>? = null,
    val transactions: TxnPage? = null,
    val filter: JsonObject? = null,
    /** Corrections Ask proposed; nothing changes until the user applies one. */
    val proposals: List<ChangeProposal>? = null,
)

/** The fields a correction reads and writes on one row. */
@Serializable
data class ChangeFields(
    val kind: String = "",
    val category: String? = null,
    val subcategory: String? = null,
    val merchant: String? = null,
    val reviewState: String = "unreviewed",
    val tags: List<String> = emptyList(),
)

/** What a proposal sets; absent fields are left alone. */
@Serializable
data class ChangeSet(
    val kind: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val merchant: String? = null,
    val reviewState: String? = null,
)

/** A signed change to spending (a positive outflow) in one currency. */
@Serializable
data class SpendingChange(val currency: String, val change: Long)

@Serializable
data class ChangeSample(
    val id: String,
    val date: String,
    val description: String = "",
    val accountId: String = "",
    val accountName: String = "",
    val amount: Money,
    val before: ChangeFields,
    val after: ChangeFields,
)

/**
 * A ledger correction Ask proposed. It snapshots the rows it matched; applying
 * it is the user's own edit, and undo puts back what it replaced.
 */
@Serializable
data class ChangeProposal(
    val id: String,
    /** `proposed`, `applied`, `dismissed` or `undone`. */
    val status: String = "proposed",
    val title: String = "",
    val set: ChangeSet = ChangeSet(),
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList(),
    /** Rows the change alters, and rows that already matched it. */
    val rows: Int = 0,
    val unchanged: Int = 0,
    val totals: List<CurrencyAmount> = emptyList(),
    val spending: List<SpendingChange> = emptyList(),
    val sample: List<ChangeSample> = emptyList(),
    val applied: Int? = null,
    val skipped: Int? = null,
    val restored: Int? = null,
    val createdAt: String = "",
    val appliedAt: String? = null,
    val undoneAt: String? = null,
    val dismissedAt: String? = null,
)

/** A tool the answer is using, shown as a step while it runs. */
@Serializable
data class AskStep(
    val id: String,
    val name: String = "",
    val state: String = "running",
    val label: String = "",
)

/** One server-sent event of a streamed answer. */
sealed interface AskEvent {
    data class Step(val step: AskStep) : AskEvent
    data class Data(val partial: QueryResult) : AskEvent
    data class Text(val delta: String) : AskEvent
    /** Text so far was preamble before a tool call; drop it. */
    data object Discard : AskEvent
    data class Done(val result: QueryResult) : AskEvent
}

@Serializable
data class TextDelta(val delta: String = "")
