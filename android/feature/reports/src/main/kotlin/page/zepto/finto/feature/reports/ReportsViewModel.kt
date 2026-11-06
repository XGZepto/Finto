package page.zepto.finto.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.DEFAULT_BASE_CURRENCY
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Spending
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** One loaded report: every figure on the page and the filter they came from. */
data class Report(
    val split: String,
    /** The filter behind the split, so a tapped band opens exactly its rows. */
    val scope: LedgerFilter,
    val totals: FlowTotals?,
    val prior: FlowTotals?,
    val bands: List<Band>,
    val months: List<MonthSpend>,
    /** Mean outflow of the complete months, the chart's reference line. */
    val mean: Long?,
    val unconvertible: List<String>,
) {
    val delta: SpendDelta? get() = ReportMath.spendDelta(totals, prior)
    val savingsRate: Double? get() = ReportMath.savingsRate(totals)
    val topBands: List<Band> get() = bands.take(ReportMath.TOP_BANDS)

    fun drill(bucket: String): LedgerFilter = ReportMath.drill(split, bucket, scope)
}

data class ReportsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = DEFAULT_BASE_CURRENCY,
    val period: ReportPeriod = ReportPeriod.ThreeMonths,
    val split: String = ReportMath.DEFAULT_SPLIT,
    val accounts: List<Account> = emptyList(),
    /** Null for all accounts. */
    val accountId: String? = null,
    val selectedMonths: List<String> = emptyList(),
    /** The window the period covers, for the subtitle. */
    val window: DateWindow? = null,
    val report: Report? = null,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }
    val comparisonLabel: String get() = ReportMath.comparisonLabel(period, selectedMonths.size)
}

@HiltViewModel
class ReportsViewModel internal constructor(
    private val ledger: LedgerRepository,
    session: SessionRepository,
    private val clock: Clock,
) : ViewModel() {

    @Inject
    constructor(ledger: LedgerRepository, session: SessionRepository) : this(ledger, session, Clock.systemDefaultZone())

    private val mutableState = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // The selector can do without names; a failure leaves "All accounts" only.
            staleWhileRevalidate { policy -> ledger.accounts(policy) }
                .catch { }
                .collect { accounts -> mutableState.update { it.copy(accounts = accounts) } }
        }
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect { currency ->
                    mutableState.update { it.copy(currency = currency) }
                    load()
                }
        }
    }

    fun setPeriod(period: ReportPeriod) {
        if (period == mutableState.value.period && mutableState.value.selectedMonths.isEmpty()) return
        mutableState.update { it.copy(period = period, selectedMonths = emptyList()) }
        load()
    }

    fun setSplit(split: String) {
        if (split == mutableState.value.split) return
        mutableState.update { it.copy(split = split) }
        load()
    }

    /** Null for all accounts. */
    fun setAccount(accountId: String?) {
        if (accountId == mutableState.value.accountId) return
        mutableState.update { it.copy(accountId = accountId) }
        load()
    }

    fun toggleMonth(month: String) {
        mutableState.update { it.copy(selectedMonths = ReportMath.toggle(it.selectedMonths, month)) }
        load()
    }

    fun clearMonths() {
        if (mutableState.value.selectedMonths.isEmpty()) return
        mutableState.update { it.copy(selectedMonths = emptyList()) }
        load()
    }

    fun refresh() = load(refresh = true)

    /** The error has been shown (as a snackbar over content). */
    fun errorShown() = mutableState.update { it.copy(error = null) }

    private fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        val query = mutableState.value
        val today = LocalDate.now(clock)
        val requests = ReportMath.requests(query.period, query.selectedMonths, query.accountId, today)
        mutableState.update {
            it.copy(loading = true, refreshing = refresh, error = null, window = ReportMath.windows(query.period, today).first)
        }
        loadJob = viewModelScope.launch {
            suspendRunCatching {
                staleWhileRevalidate(refresh) { policy -> fetch(query.split, query.currency, requests, YearMonth.from(today), policy) }
                    .collect { report -> mutableState.update { it.copy(report = report) } }
            }
                .onSuccess { mutableState.update { it.copy(loading = false, refreshing = false) } }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) }
                }
        }
    }

    private suspend fun fetch(
        split: String,
        currency: String,
        requests: ReportRequests,
        thisMonth: YearMonth,
        policy: ReadPolicy,
    ): Report = coroutineScope {
        val main = async { ledger.summary(split, requests.main, currency, policy) }
        // The comparison and the month chart are extras: without them the report still stands.
        val prior = requests.prior?.let { filter ->
            async { suspendRunCatching { ledger.summary("kind", filter, currency, policy) }.getOrNull() }
        }
        val months = async { suspendRunCatching { ledger.summary("month", requests.months, currency, policy) }.getOrNull() }

        val response = main.await()
        val monthly = ReportMath.monthlySpend(months.await()?.rows.orEmpty())
        Report(
            split = split,
            scope = requests.main,
            totals = response.normalised?.total,
            prior = prior?.await()?.normalised?.total,
            bands = ReportMath.bands(response.rows, currency),
            months = monthly,
            mean = ReportMath.monthlyMean(monthly, thisMonth),
            unconvertible = Spending.unconvertible(response.rows),
        )
    }
}
