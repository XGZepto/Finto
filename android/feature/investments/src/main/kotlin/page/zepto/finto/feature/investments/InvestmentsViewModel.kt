package page.zepto.finto.feature.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.InvestmentsRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.model.InvestmentActivity
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentSnapshot
import page.zepto.finto.core.model.InvestmentSubaccount
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ValuePoint
import javax.inject.Inject

data class InvestmentsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The snapshot list could not be read and nothing is shown. */
    val error: String? = null,
    /** Something failed while content stays on screen. */
    val notice: String? = null,
    val snapshots: List<InvestmentSnapshot> = emptyList(),
    val scheme: String? = null,
    /** A member account the page is scoped to. */
    val accountId: String? = null,
    val current: InvestmentDetail? = null,
    val opening: Boolean = false,
    /** The chosen snapshot could not be opened and there is no other to show. */
    val openError: String? = null,
    val history: List<ValuePoint> = emptyList(),
    val activities: List<InvestmentActivity> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
) {
    val schemes: List<String> get() = snapshots.map { it.scheme }.distinct()

    /** Valuations of the scheme on show, newest first. */
    val scopedSnapshots: List<InvestmentSnapshot> get() = snapshots.filter { scheme == null || it.scheme == scheme }

    val selectedSubaccount: InvestmentSubaccount? get() = accountId?.let { id -> current?.subaccounts?.firstOrNull { it.accountId == id } }

    /** The member account's balance when one is chosen, otherwise the scheme total. */
    val displayedValue: Money? get() = selectedSubaccount?.balance ?: current?.total

    val visibleHistory: List<ValuePoint> get() = Investments.visibleHistory(history, current?.asOfDate)

    val change: ValueChange? get() = Investments.change(visibleHistory)

    val title: String
        get() = accountId?.let(::accountLabel) ?: current?.scheme?.let(Investments::friendly) ?: "Investments"

    fun accountLabel(id: String): String = accountNames[id] ?: Investments.friendly(id)
}

/**
 * MPF positions: units, not cash, so a dated snapshot rather than a balance.
 * Choosing a member account scopes the value, history and contributions to it.
 */
@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    private val investments: InvestmentsRepository,
    private val ledger: LedgerRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(InvestmentsUiState())
    val state: StateFlow<InvestmentsUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var openJob: Job? = null
    private var historyJob: Job? = null
    private var activitiesJob: Job? = null
    private var requested: String? = null

    init {
        load()
    }

    fun refresh() = load(refresh = true)

    fun noticeShown() = mutableState.update { it.copy(notice = null) }

    /** Another valuation date of the same scheme. */
    fun selectSnapshot(id: String) {
        if (id == mutableState.value.current?.id && requested == id) return
        openJob?.cancel()
        openJob = viewModelScope.open(id, refresh = false, failures = null)
    }

    fun selectScheme(scheme: String) {
        if (scheme == mutableState.value.scheme) return
        mutableState.update { it.copy(scheme = scheme, accountId = null, history = emptyList()) }
        mutableState.value.scopedSnapshots.firstOrNull()?.let { selectSnapshot(it.id) }
        reloadScoped()
    }

    fun selectAccount(accountId: String) {
        if (accountId == mutableState.value.accountId) return
        mutableState.update { it.copy(accountId = accountId, history = emptyList(), activities = emptyList()) }
        reloadScoped()
    }

    /** Back from a member account to the whole scheme. */
    fun clearAccount() {
        if (mutableState.value.accountId == null) return
        mutableState.update { it.copy(accountId = null, history = emptyList(), activities = emptyList()) }
        reloadScoped()
    }

    private fun reloadScoped() {
        historyJob?.cancel()
        activitiesJob?.cancel()
        historyJob = viewModelScope.history(refresh = false, failures = null)
        activitiesJob = viewModelScope.activities(refresh = false, failures = null)
    }

    private fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        mutableState.update {
            it.copy(loading = !refresh && it.snapshots.isEmpty(), refreshing = refresh, error = null, notice = null)
        }
        loadJob = viewModelScope.launch {
            val failures = mutableListOf<Throwable>()
            var listFailure: Throwable? = null
            coroutineScope {
                launch {
                    revalidate(refresh) { policy -> ledger.accounts(policy) }.collect { result ->
                        // Names are a nicety: without them the ids are humanised.
                        result.onSuccess { accounts -> mutableState.update { it.copy(accountNames = accounts.associate { a -> a.id to a.displayName }) } }
                    }
                }
                activitiesJob?.cancel()
                activitiesJob = activities(refresh, failures)
                revalidate(refresh) { policy -> investments.snapshots(policy) }.collect { result ->
                    result
                        .onSuccess { snapshots -> showSnapshots(snapshots, refresh, failures) }
                        .onFailure { listFailure = it }
                }
            }
            val failure = listFailure ?: failures.firstOrNull()
            mutableState.update {
                val shown = it.snapshots.isNotEmpty()
                val listFailed = listFailure != null && !shown
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = if (listFailed) ApiErrors.message(listFailure!!) else null,
                    notice = failure?.takeIf { _ -> !listFailed }?.let(ApiErrors::message),
                )
            }
        }
    }

    /** A new snapshot list: keep the scheme and valuation on show when they still exist, else the newest. */
    private fun CoroutineScope.showSnapshots(snapshots: List<InvestmentSnapshot>, refresh: Boolean, failures: MutableList<Throwable>) {
        val before = mutableState.value
        val scheme = before.scheme?.takeIf { s -> snapshots.any { it.scheme == s } } ?: snapshots.firstOrNull()?.scheme
        mutableState.update { it.copy(loading = false, snapshots = snapshots, scheme = scheme) }
        val scoped = mutableState.value.scopedSnapshots
        val target = scoped.firstOrNull { it.id == requested }?.id ?: scoped.firstOrNull()?.id ?: return
        openJob?.cancel()
        openJob = open(target, refresh, failures)
        if (historyJob?.isActive != true || refresh || scheme != before.scheme) {
            historyJob?.cancel()
            historyJob = history(refresh, failures)
        }
    }

    private fun CoroutineScope.open(id: String, refresh: Boolean, failures: MutableList<Throwable>?): Job {
        requested = id
        mutableState.update { it.copy(opening = true, openError = null) }
        return launch {
            revalidate(refresh) { policy -> investments.snapshot(id, policy) }.collect { result ->
                if (requested != id) return@collect
                result
                    .onSuccess { detail -> mutableState.update { it.copy(current = detail, opening = false, openError = null) } }
                    .onFailure { error ->
                        val message = ApiErrors.message(error)
                        mutableState.update {
                            val stillShown = it.current?.id == id
                            it.copy(
                                opening = false,
                                openError = message.takeIf { _ -> it.current == null },
                                notice = if (failures == null && !stillShown) message else it.notice,
                            )
                        }
                        failures?.add(error)
                    }
            }
        }
    }

    private fun CoroutineScope.history(refresh: Boolean, failures: MutableList<Throwable>?): Job {
        val scheme = mutableState.value.scheme
        val accountId = mutableState.value.accountId
        return launch {
            revalidate(refresh) { policy -> investments.history(scheme, accountId, policy) }.collect { result ->
                val now = mutableState.value
                if (now.scheme != scheme || now.accountId != accountId) return@collect
                result
                    .onSuccess { history -> mutableState.update { it.copy(history = history.points) } }
                    .onFailure { failures?.add(it) }
            }
        }
    }

    private fun CoroutineScope.activities(refresh: Boolean, failures: MutableList<Throwable>?): Job {
        val accountId = mutableState.value.accountId
        return launch {
            revalidate(refresh) { policy -> investments.activities(accountId, policy) }.collect { result ->
                if (mutableState.value.accountId != accountId) return@collect
                result
                    .onSuccess { activities -> mutableState.update { it.copy(activities = activities) } }
                    .onFailure { failures?.add(it) }
            }
        }
    }
}
