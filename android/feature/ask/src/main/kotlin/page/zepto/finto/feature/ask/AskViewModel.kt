package page.zepto.finto.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.AskRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.model.AskEvent
import page.zepto.finto.core.model.AskTurnContext
import page.zepto.finto.core.model.QueryResult
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

data class AskUiState(
    val turns: List<AskTurn> = emptyList(),
    /** Turns whose grouped rows are shown in full rather than the top few. */
    val expanded: Set<Long> = emptySet(),
    val accountNames: Map<String, String> = emptyMap(),
) {
    val asking: Boolean get() = turns.any { it.state == TurnState.Asking }
}

/** One-off things the screen does in response, such as a haptic tick when an answer lands. */
sealed interface AskEffect {
    data class Finished(val ok: Boolean) : AskEffect
}

@HiltViewModel
class AskViewModel @Inject constructor(
    private val repository: AskRepository,
    private val thread: AskThread,
    private val ledger: LedgerRepository,
) : ViewModel() {

    /** Milliseconds now; replaced in tests so timings are fixed. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val mutableState = MutableStateFlow(
        // A turn still asking when Ask was left lost its stream; the web drops it too.
        AskUiState(turns = thread.turns.filter { it.state != TurnState.Asking }),
    )
    val state: StateFlow<AskUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<AskEffect>(Channel.BUFFERED)
    val effects: Flow<AskEffect> = effectChannel.receiveAsFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            staleWhileRevalidate { policy -> ledger.accounts(policy) }
                .catch { /* Ids still name the account. */ }
                .collect { accounts -> update { it.copy(accountNames = accounts.associate { a -> a.id to a.displayName }) } }
        }
    }

    /** Sends [question] with the last few answered turns as context; false when it cannot be asked now. */
    fun ask(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty() || mutableState.value.asking) return false
        val history = mutableState.value.turns
            .filter { it.state == TurnState.Done && it.text.isNotEmpty() }
            .takeLast(HISTORY)
            .map { AskTurnContext(it.question, it.text.take(HISTORY_CHARS)) }
        val turn = AskTurn(id = ++thread.sequence, question = text, startedAt = clock())
        update { it.copy(turns = (it.turns + turn).takeLast(AskThread.KEEP)) }

        job = viewModelScope.launch {
            var opened = false
            try {
                repository.ask(text, history).collect { event ->
                    opened = true
                    apply(turn.id, event)
                }
                // A stream that closed without its `done` still ends the turn, with what arrived.
                finish(turn.id, null, CUT_OFF)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                finish(turn.id, null, if (opened) CUT_OFF else ApiErrors.message(error))
            }
        }
        return true
    }

    fun stop() {
        val current = mutableState.value.turns.firstOrNull { it.state == TurnState.Asking } ?: return
        job?.cancel()
        job = null
        finish(current.id, null, STOPPED)
    }

    fun newConversation() {
        stop()
        update { it.copy(turns = emptyList(), expanded = emptySet()) }
    }

    fun toggleRows(turnId: Long) = update { state ->
        state.copy(expanded = if (turnId in state.expanded) state.expanded - turnId else state.expanded + turnId)
    }

    private fun apply(id: Long, event: AskEvent) {
        when (event) {
            is AskEvent.Step -> patch(id) { it.copy(steps = AskAnswers.upsert(it.steps, event.step)) }
            is AskEvent.Data -> patch(id) { it.copy(data = AskAnswers.merge(it.data, event.partial)) }
            is AskEvent.Text -> patch(id) { it.copy(text = it.text + event.delta) }
            AskEvent.Discard -> patch(id) { it.copy(text = "") }
            is AskEvent.Done -> finish(id, event.result, null)
        }
    }

    private fun finish(id: Long, result: QueryResult?, error: String?) {
        val turn = mutableState.value.turns.firstOrNull { it.id == id } ?: return
        if (turn.state != TurnState.Asking) return
        val done = result?.takeIf { it.ok }
        val ok = done != null
        patch(id) { t ->
            t.copy(
                state = if (done != null) TurnState.Done else TurnState.Error,
                error = if (done != null) null else result?.error ?: error ?: "Ask could not finish.",
                text = done?.answer ?: t.text,
                data = done?.let { AskAnswers.merge(t.data, it) } ?: t.data,
                steps = t.steps.map { step -> if (step.state == "running") step.copy(state = "done") else step },
                seconds = (clock() - t.startedAt) / 1000.0,
            )
        }
        effectChannel.trySend(AskEffect.Finished(ok))
    }

    private fun patch(id: Long, change: (AskTurn) -> AskTurn) = update { state ->
        state.copy(turns = state.turns.map { if (it.id == id) change(it) else it })
    }

    private fun update(change: (AskUiState) -> AskUiState) {
        mutableState.value = change(mutableState.value)
        thread.turns = mutableState.value.turns
    }

    override fun onCleared() {
        // Leaving mid-answer ends the stream; keep the question, marked as stopped.
        thread.turns = mutableState.value.turns.map { turn ->
            if (turn.state == TurnState.Asking) turn.copy(state = TurnState.Error, error = STOPPED) else turn
        }
    }

    companion object {
        const val CUT_OFF = "The answer was cut off. Try again."
        const val STOPPED = "Stopped."
        private const val HISTORY = 3
        private const val HISTORY_CHARS = 800
    }
}
