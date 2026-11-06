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
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.IntegrityReport
import javax.inject.Inject

data class IntegrityUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val report: IntegrityReport? = null,
    /** All periods, or only the ones that do not reconcile. */
    val showAll: Boolean = false,
    val message: String? = null,
) {
    val checks: List<BalanceCheck> get() = IntegrityLogic.visibleChecks(report, showAll)
}

@HiltViewModel
class IntegrityViewModel @Inject constructor(
    private val ledger: LedgerRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(IntegrityUiState())
    val state: StateFlow<IntegrityUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var toggled = false

    init {
        load(refresh = false)
    }

    /** Re-run: the report is computed on request, so this always asks the server. */
    fun refresh() = load(refresh = true)

    fun showAll(all: Boolean) {
        toggled = true
        mutableState.update { it.copy(showAll = all) }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private fun load(refresh: Boolean) {
        job?.cancel()
        mutableState.update { it.copy(loading = it.report == null, refreshing = refresh, error = null) }
        job = viewModelScope.launch {
            staleWhileRevalidate(refresh) { policy -> ledger.integrity(policy) }
                .catch { error ->
                    val reason = ApiErrors.message(error)
                    mutableState.update { state ->
                        if (state.report == null) {
                            state.copy(loading = false, refreshing = false, error = reason)
                        } else {
                            state.copy(refreshing = false, message = "Couldn't re-run the check. $reason")
                        }
                    }
                }
                .collect { report ->
                    mutableState.update { state ->
                        state.copy(
                            loading = false,
                            report = report,
                            // When everything reconciles there is nothing to triage: show the passing periods.
                            showAll = if (toggled) state.showAll else report.summary.discrepancyCount == 0,
                        )
                    }
                }
            mutableState.update { it.copy(refreshing = false) }
        }
    }
}
