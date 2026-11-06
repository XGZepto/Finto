package page.zepto.finto.feature.recurring

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
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

data class RecurringUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Nothing could be loaded. */
    val error: String? = null,
    /** Something failed to refresh; what is shown is the last copy. */
    val notice: String? = null,
    val currency: String = "HKD",
    val filter: RecurringFilter = RecurringFilter(),
    val commitments: List<Commitment> = emptyList(),
    val plans: List<InstallmentPlan> = emptyList(),
    val view: RecurringView = RecurringView(monthlyTotal = Money(0, currency)),
    val inactiveExpanded: Boolean = false,
) {
    val hasData: Boolean get() = commitments.isNotEmpty() || plans.isNotEmpty()
}

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RecurringUiState())
    val state: StateFlow<RecurringUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect { currency ->
                    mutableState.update { it.copy(currency = currency).recomputed() }
                    load()
                }
        }
    }

    fun refresh() = load(refresh = true)

    fun setSearch(search: String) = updateFilter { it.copy(search = search) }

    fun setKind(kind: RecurringKind) = updateFilter { it.copy(kind = kind) }

    fun setCadence(cadence: Cadence?) = updateFilter { it.copy(cadence = cadence) }

    fun toggleInactive() = mutableState.update { it.copy(inactiveExpanded = !it.inactiveExpanded) }

    fun noticeShown() = mutableState.update { it.copy(notice = null) }

    private fun updateFilter(change: (RecurringFilter) -> RecurringFilter) =
        mutableState.update { it.copy(filter = change(it.filter)).recomputed() }

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refresh && !it.hasData, refreshing = refresh, error = null, notice = null) }
        job = viewModelScope.launch {
            val currency = mutableState.value.currency
            val today = LocalDate.now(ZoneOffset.UTC)
            var last: RecurringData? = null
            ledger.recurringData(currency, refresh).collect { data ->
                last = data
                mutableState.update {
                    // A read that failed outright leaves the copy already on screen in place.
                    val chargesLost = data.chargesFailure != null && data.charges.isEmpty()
                    val plansLost = data.plansFailure != null && data.plans.isEmpty()
                    it.copy(
                        loading = false,
                        commitments = if (chargesLost) it.commitments else Recurring.commitments(data.charges, today),
                        plans = if (plansLost) it.plans else data.plans,
                    ).recomputed()
                }
            }
            val failure = last?.failure
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = failure?.takeIf { _ -> !it.hasData }?.let(ApiErrors::message),
                    notice = failure?.takeIf { _ -> it.hasData }?.let(ApiErrors::message),
                )
            }
        }
    }

    private fun RecurringUiState.recomputed() = copy(view = Recurring.view(commitments, plans, filter, currency))
}
