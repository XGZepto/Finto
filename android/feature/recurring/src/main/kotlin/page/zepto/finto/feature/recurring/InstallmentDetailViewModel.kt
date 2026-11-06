package page.zepto.finto.feature.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.InstallmentPlan
import javax.inject.Inject

data class InstallmentDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val plan: InstallmentPlan? = null,
    val schedule: List<ScheduleRow> = emptyList(),
)

@HiltViewModel
class InstallmentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: LedgerRepository,
) : ViewModel() {

    // Type-safe routes store each property under its own name.
    private val id: String = checkNotNull(savedStateHandle["id"]) { "InstallmentDestination needs an id" }
    private val mutableState = MutableStateFlow(InstallmentDetailUiState())
    val state: StateFlow<InstallmentDetailUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun refresh() = load(refresh = true)

    fun noticeShown() = mutableState.update { it.copy(notice = null) }

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refresh && it.plan == null, refreshing = refresh, error = null, notice = null) }
        job = viewModelScope.launch {
            suspendRunCatching { ledger.installment(id) }
                .onSuccess { plan ->
                    mutableState.update {
                        it.copy(loading = false, refreshing = false, plan = plan, schedule = InstallmentSchedule.of(plan))
                    }
                }
                .onFailure { failure ->
                    val message = ApiErrors.message(failure)
                    mutableState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = message.takeIf { _ -> it.plan == null },
                            notice = message.takeIf { _ -> it.plan != null },
                        )
                    }
                }
        }
    }
}
