package page.zepto.finto.feature.ask

import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.QueryResult
import javax.inject.Inject
import javax.inject.Singleton

enum class TurnState { Asking, Done, Error }

/** One question and everything that came back for it. */
data class AskTurn(
    val id: Long,
    val question: String,
    val state: TurnState = TurnState.Asking,
    val steps: List<AskStep> = emptyList(),
    /** The answer as it streams; replaced by the final text when it is done. */
    val text: String = "",
    /** Figures as the tools return them, then the complete result. */
    val data: QueryResult = QueryResult(),
    val error: String? = null,
    val startedAt: Long = 0,
    val seconds: Double? = null,
)

/**
 * The conversation, kept for as long as the app runs (the web keeps it for the
 * browser session), so leaving Ask and coming back finds the thread as it was.
 */
@Singleton
class AskThread @Inject constructor() {
    @Volatile
    var turns: List<AskTurn> = emptyList()

    @Volatile
    var sequence: Long = 0

    companion object {
        /** How many turns a thread keeps. */
        const val KEEP = 12
    }
}
