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
