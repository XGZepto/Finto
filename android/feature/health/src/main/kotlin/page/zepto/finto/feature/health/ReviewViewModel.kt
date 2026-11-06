package page.zepto.finto.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.ReviewRepository
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue
import page.zepto.finto.core.model.ReviewQueueResponse
import javax.inject.Inject

/** One queue as last read, less what was decided here since. */
data class QueueState(
    val items: List<ReviewCandidate> = emptyList(),
    val total: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class ReviewUiState(
    val queue: ReviewQueue = ReviewQueue.Duplicates,
    val queues: Map<ReviewQueue, QueueState> = emptyMap(),
    val cursor: Int = 0,
    val refreshing: Boolean = false,
    /** Account ids to display names; a decision is made on names, not storage ids. */
    val accountNames: Map<String, String> = emptyMap(),
    /** A failure to tell the reader about once, without hiding what is shown. */
    val message: String? = null,
) {
    val current: QueueState get() = queues[queue] ?: QueueState()
    val items: List<ReviewCandidate> get() = current.items
    val candidate: ReviewCandidate? get() = items.getOrNull(cursor)
    val loading: Boolean get() = !current.loaded && current.error == null
    fun count(queue: ReviewQueue): Int = queues[queue]?.takeIf { it.loaded }?.total ?: 0
    fun accountName(id: String?): String = id?.let { accountNames[it] ?: it }.orEmpty()
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviews: ReviewRepository,
    private val ledger: LedgerRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    /** Until a tab is picked, the screen opens on work rather than on an empty queue. */
    private var picked = false
    private val jobs = mutableMapOf<ReviewQueue, Job>()
    private var loadJob: Job? = null

    /** Decisions sent but not yet confirmed, so a late read cannot bring them back. */
    private val pending = mutableMapOf<ReviewQueue, MutableSet<String>>()

    init {
        load(refresh = false)
        viewModelScope.launch {
            staleWhileRevalidate { policy -> ledger.accounts(policy) }
                .catch { /* Names are a nicety: ids still identify the account. */ }
                .collect { accounts ->
                    mutableState.update { state -> state.copy(accountNames = accounts.associate { it.id to it.displayName }) }
                }
        }
    }

    fun refresh() = load(refresh = true)

    /** Reads one queue again after it failed. */
    fun retry() = loadQueue(mutableState.value.queue, refresh = true)

    fun select(queue: ReviewQueue) {
        picked = true
        mutableState.update { it.copy(queue = queue, cursor = 0) }
        val target = mutableState.value.queues[queue]
        if (target?.error != null && jobs[queue]?.isActive != true) loadQueue(queue, refresh = true)
    }

    fun move(delta: Int) {
        mutableState.update { state ->
            val next = state.cursor + delta
            if (next < 0 || next >= state.items.size) state else state.copy(cursor = next)
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    /**
     * Takes the decision off the list at once and sends it; a failed send puts
     * the candidate back where it was and says why.
     */
    fun resolve(accept: Boolean) {
        val state = mutableState.value
        val queue = state.queue
        val item = state.candidate ?: return
        val index = state.cursor
        pending.getOrPut(queue) { mutableSetOf() } += item.id
        mutableState.update { current ->
            val target = current.queues[queue] ?: QueueState()
            val items = target.items.filterNot { it.id == item.id }
            current.copy(
                queues = current.queues + (queue to target.copy(items = items, total = (target.total - 1).coerceAtLeast(0))),
                cursor = current.cursor.coerceAtMost((items.size - 1).coerceAtLeast(0)),
            )
        }
        viewModelScope.launch {
            val result = suspendRunCatching { reviews.resolve(queue, item.id, accept) }
            pending[queue]?.remove(item.id)
            result.onFailure { error -> restore(queue, item, index, accept, ApiErrors.message(error)) }
        }
    }

    private fun restore(queue: ReviewQueue, item: ReviewCandidate, index: Int, accept: Boolean, reason: String) {
        mutableState.update { current ->
            val target = current.queues[queue] ?: QueueState(loaded = true)
            if (target.items.any { it.id == item.id }) return@update current
            val at = index.coerceIn(0, target.items.size)
            val items = target.items.toMutableList().apply { add(at, item) }
            current.copy(
                queues = current.queues + (queue to target.copy(items = items, total = target.total + 1)),
                cursor = if (current.queue == queue) at else current.cursor,
                message = "Couldn't ${if (accept) "accept" else "reject"} it. $reason",
            )
        }
    }

    private fun load(refresh: Boolean) {
        loadJob?.cancel()
        mutableState.update { it.copy(refreshing = refresh) }
        loadJob = viewModelScope.launch {
            ReviewQueue.entries.map { loadQueue(it, refresh) }.joinAll()
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    private fun loadQueue(queue: ReviewQueue, refresh: Boolean): Job {
        jobs[queue]?.cancel()
        mutableState.update { state ->
            state.queues[queue]?.takeIf { it.error != null }
                ?.let { state.copy(queues = state.queues + (queue to it.copy(error = null))) }
                ?: state
        }
        return viewModelScope.launch {
            staleWhileRevalidate(refresh) { policy -> reviews.queue(queue, policy) }
                .catch { error -> failed(queue, ApiErrors.message(error)) }
                .collect { response -> loaded(queue, response) }
        }.also { jobs[queue] = it }
    }

    private fun loaded(queue: ReviewQueue, response: ReviewQueueResponse) {
        val decided = pending[queue].orEmpty()
        val items = response.items.filterNot { it.id in decided }
        val removed = response.items.size - items.size
        mutableState.update { state ->
            val queues = state.queues + (queue to QueueState(items, (response.total - removed).coerceAtLeast(items.size), loaded = true))
            val cursor = if (state.queue == queue) state.cursor.coerceAtMost((items.size - 1).coerceAtLeast(0)) else state.cursor
            openFirstWithWork(state.copy(queues = queues, cursor = cursor))
        }
    }

    private fun failed(queue: ReviewQueue, reason: String) {
        mutableState.update { state ->
            val target = state.queues[queue] ?: QueueState()
            if (target.loaded) {
                // Keep the copy on screen; only the queue being read gets a word about it.
                if (state.queue == queue) state.copy(message = "Couldn't refresh. $reason") else state
            } else {
                openFirstWithWork(state.copy(queues = state.queues + (queue to target.copy(error = reason))))
            }
        }
    }

    private fun openFirstWithWork(state: ReviewUiState): ReviewUiState {
        if (picked || state.items.isNotEmpty()) return state
        val settled = ReviewQueue.entries.all { state.queues[it]?.let { q -> q.loaded || q.error != null } == true }
        if (!settled) return state
        val next = ReviewQueue.entries.firstOrNull { state.count(it) > 0 } ?: return state
        return if (next == state.queue) state else state.copy(queue = next, cursor = 0)
    }
}
