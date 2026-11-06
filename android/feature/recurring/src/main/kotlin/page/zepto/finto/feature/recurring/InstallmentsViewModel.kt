package page.zepto.finto.feature.recurring

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
import page.zepto.finto.core.model.InstallmentsResponse
import javax.inject.Inject

data class InstallmentsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val activeOnly: Boolean = true,
    val response: InstallmentsResponse? = null,
) {
    val plans get() = response?.plans.orEmpty()
    val activeCount: Int get() = plans.count { it.isActive }
}

/**
 * Plans with what is still owed. Outstanding is never summed across
 * currencies: a HKD plan and a USD plan do not add up to one liability.
 */
@HiltViewModel
class InstallmentsViewModel @Inject constructor(
    private val ledger: LedgerRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(InstallmentsUiState())
    val state: StateFlow<InstallmentsUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun refresh() = load(refresh = true)

    fun setActiveOnly(activeOnly: Boolean) {
        if (activeOnly == mutableState.value.activeOnly) return
        // The other list is a different read; show its skeleton rather than the wrong plans.
        mutableState.update { it.copy(activeOnly = activeOnly, response = null) }
        load()
    }

    fun noticeShown() = mutableState.update { it.copy(notice = null) }

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refresh && it.response == null, refreshing = refresh, error = null, notice = null) }
        val activeOnly = mutableState.value.activeOnly
        job = viewModelScope.launch {
            var failure: Throwable? = null
            revalidate(refresh) { policy -> ledger.installments(activeOnly, policy) }.collect { result ->
                result
                    .onSuccess { response -> mutableState.update { it.copy(loading = false, response = response) } }
                    .onFailure { failure = it }
            }
            mutableState.update {
                val message = failure?.let(ApiErrors::message)
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = message?.takeIf { _ -> it.response == null },
                    notice = message?.takeIf { _ -> it.response != null },
                )
            }
        }
    }
}
