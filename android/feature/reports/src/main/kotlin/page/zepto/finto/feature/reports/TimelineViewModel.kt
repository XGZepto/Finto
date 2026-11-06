package page.zepto.finto.feature.reports

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
import page.zepto.finto.core.data.repository.DEFAULT_BASE_CURRENCY
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.Coverage
import javax.inject.Inject

data class TimelineUiState(
    /** The composition is in flight. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = DEFAULT_BASE_CURRENCY,
    val dimension: String = TimelineMath.DEFAULT_DIMENSION,
    val scale: TimelineScale = TimelineScale.Share,
    val composition: Composition? = null,
    /** Null until loaded, and left out when it cannot be. */
    val coverage: Coverage? = null,
) {
    val columns: List<TimelineColumn> get() = composition?.let { TimelineMath.columns(it, scale) }.orEmpty()

    /** Account ids to names, from the coverage grid, for the account dimension. */
    val accountNames: Map<String, String> get() = coverage?.accounts?.associate { it.accountId to it.accountName }.orEmpty()
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = mutableState.asStateFlow()
    private var compositionJob: Job? = null
    private var coverageJob: Job? = null

    init {
        loadCoverage()
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect { currency ->
                    mutableState.update { it.copy(currency = currency, composition = null) }
                    loadComposition()
                }
        }
    }

    fun setDimension(dimension: String) {
        if (dimension == mutableState.value.dimension) return
        // The old bands would be named for the wrong dimension: clear them.
        mutableState.update { it.copy(dimension = dimension, composition = null) }
        loadComposition()
    }

    fun setScale(scale: TimelineScale) = mutableState.update { it.copy(scale = scale) }

    fun refresh() {
        loadComposition(refresh = true)
        loadCoverage(refresh = true)
    }

    fun errorShown() = mutableState.update { it.copy(error = null) }

    private fun loadComposition(refresh: Boolean = false) {
        compositionJob?.cancel()
        val (dimension, currency) = mutableState.value.let { it.dimension to it.currency }
        mutableState.update { it.copy(loading = true, refreshing = refresh, error = null) }
        compositionJob = viewModelScope.launch {
            suspendRunCatching {
                staleWhileRevalidate(refresh) { policy -> ledger.composition(dimension, currency, policy) }
                    .collect { composition -> mutableState.update { it.copy(composition = composition) } }
            }
                .onSuccess { mutableState.update { it.copy(loading = false, refreshing = false) } }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) }
                }
        }
    }

    private fun loadCoverage(refresh: Boolean = false) {
        coverageJob?.cancel()
        coverageJob = viewModelScope.launch {
            // As on the web, coverage that cannot load is simply not shown.
            suspendRunCatching {
                staleWhileRevalidate(refresh) { policy -> ledger.coverage(policy) }
                    .collect { coverage -> mutableState.update { it.copy(coverage = coverage) } }
            }
        }
    }
}
