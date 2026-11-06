package page.zepto.finto.feature.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

data class RecurringDetailUiState(
    val key: String,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val commitment: Commitment? = null,
)

/** One commitment, recomputed from the same tagged charges the list reads (the cached copy paints at once). */
@HiltViewModel
class RecurringDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: LedgerRepository,
    session: SessionRepository,
) : ViewModel() {

    // Type-safe routes store each property under its own name.
    private val key: String = checkNotNull(savedStateHandle["key"]) { "RecurringItemDestination needs a key" }
    private val mutableState = MutableStateFlow(RecurringDetailUiState(key))
    val state: StateFlow<RecurringDetailUiState> = mutableState.asStateFlow()
    private var currency = "HKD"
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect {
                    currency = it
                    load()
                }
        }
    }

    fun refresh() = load(refresh = true)

    fun noticeShown() = mutableState.update { it.copy(notice = null) }

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refresh && it.commitment == null, refreshing = refresh, error = null, notice = null) }
        job = viewModelScope.launch {
            val today = LocalDate.now(ZoneOffset.UTC)
            var last: RecurringData? = null
            var loaded = false
            ledger.recurringData(currency, refresh, withPlans = false).collect { data ->
                last = data
                // A failed read with nothing cached says nothing about whether the merchant exists.
                if (data.charges.isEmpty() && data.chargesFailure != null) return@collect
                loaded = true
                mutableState.update {
                    it.copy(loading = false, commitment = Recurring.commitments(data.charges, today).firstOrNull { c -> c.key == key })
                }
            }
            val failure = last?.chargesFailure
            mutableState.update {
                val shown = loaded || it.commitment != null
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = failure?.takeIf { _ -> !shown }?.let(ApiErrors::message),
                    notice = failure?.takeIf { _ -> shown }?.let(ApiErrors::message),
                )
            }
        }
    }
}
