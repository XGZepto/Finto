package page.zepto.finto.feature.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.FreshnessAccount
import page.zepto.finto.core.model.HeroAnchor
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MonthFlow
import page.zepto.finto.core.model.RankedBucket
import page.zepto.finto.core.model.Spending
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs

enum class SummaryRange(val label: String, val months: Int) {
    Quarter("3M", 3),
    HalfYear("6M", 6),
    Year("1Y", 12),
    TwoYears("2Y", 24),
}

/** "Spent this month", measured to where the ledger actually reaches. */
data class Hero(
    val anchor: LocalDate,
    /** True when the ledger stops before today, so the figure is "spent in October". */
    val lagging: Boolean,
    /** Show "through Oct 23" only for a real last-row date, not a month-end stand-in. */
    val exact: Boolean,
    val spent: Money,
    val previous: Money?,
    val income: Money,
) {
    val net: Money get() = Money(income.amount - spent.amount, spent.currency)

    /** Change against the same days of the month before, in percent. */
    val change: Double?
        get() = previous?.takeIf { it.amount != 0L }?.let { (spent.amount - it.amount) * 100.0 / it.amount }

    val savingsRate: Double?
        get() = income.amount.takeIf { it > 0 }?.let { (it - spent.amount) * 100.0 / it }
}

/** What the ranking can be split by, as on the web. */
val SummaryDimensions = listOf(
    "category", "subcategory", "tag", "merchant", "account", "institution", "card", "cardholder", "kind", "currency",
)

data class SummaryUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = "HKD",
    val range: SummaryRange = SummaryRange.Year,
    val groupBy: String = "category",
    val windowStart: String = "",
    val hero: Hero? = null,
    val ranked: List<RankedBucket> = emptyList(),
    val months: List<MonthFlow> = emptyList(),
    val unconvertible: List<String> = emptyList(),
    val stale: List<FreshnessAccount> = emptyList(),
) {
    val rankedTotal: Money get() = Money(ranked.sumOf { it.spend.amount }, currency)
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val clock: Clock = Clock.systemDefaultZone()
    private val mutableState = MutableStateFlow(SummaryUiState())
    val state: StateFlow<SummaryUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Reload whenever the reporting currency changes in Settings.
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect { currency ->
                    mutableState.update { it.copy(currency = currency) }
                    load()
                }
        }
    }

    fun setRange(range: SummaryRange) {
        if (range == mutableState.value.range) return
        mutableState.update { it.copy(range = range) }
        load()
    }

    fun setGroupBy(dimension: String) {
        if (dimension == mutableState.value.groupBy) return
        mutableState.update { it.copy(groupBy = dimension, ranked = emptyList()) }
        load()
    }

    /** The reporting currency is the user's preference, as on the web; Settings shows the same value. */
    fun setCurrency(currency: String) {
        if (currency == mutableState.value.currency) return
        viewModelScope.launch {
            session.setBaseCurrency(currency).onFailure { error ->
                mutableState.update { it.copy(error = ApiErrors.message(error)) }
            }
        }
    }

    fun refresh() = load(refreshing = true)

    /**
     * Paints the last copy from the HTTP cache at once, then the server's.
     * A failed refresh keeps what is on screen and says so.
     */
    private fun load(refreshing: Boolean = false) {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = !refreshing && it.hero == null, refreshing = refreshing, error = null) }
        loadJob = viewModelScope.launch {
            val (currency, range, groupBy) = mutableState.value.let { Triple(it.currency, it.range, it.groupBy) }
            staleWhileRevalidate(refreshing) { policy -> fetch(currency, range, groupBy, policy) }
                .catch { error ->
                    mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) }
                }
                .collect { loaded -> mutableState.value = loaded.copy(loading = false, refreshing = refreshing) }
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(currency: String, range: SummaryRange, groupBy: String, policy: ReadPolicy): SummaryUiState = coroutineScope {
        val today = LocalDate.now(clock)
        val start = HeroAnchor.windowStart(range.months, today).toString()
        // Spending only: a card payment the linker has not paired is still money changing pockets.
        val period = LedgerFilter(from = start, spendingOnly = true)
        val categories = async { ledger.summary(groupBy, period, currency, policy) }
        val months = async { ledger.summary("month", period, currency, policy) }
        // `from` today returns no rows; the API reports ledger_through regardless of the dates.
        val probe = async {
            suspendRunCatching {
                ledger.summary("kind", LedgerFilter(from = today.toString(), to = today.toString(), spendingOnly = true, maxAmount = -1), policy = policy)
            }.getOrNull()
        }
        val freshness = async { suspendRunCatching { ledger.statementFreshness(policy) }.getOrNull() }

        val monthRows = months.await().rows
        val anchor = HeroAnchor.anchor(probe.await()?.ledgerThrough, monthRows, today)
        val (currentFilter, previousFilter) = HeroAnchor.comparison(anchor.date)
        val current = async { ledger.summary("month", currentFilter, currency, policy).normalised?.total }
        val previous = async {
            suspendRunCatching { ledger.summary("month", previousFilter, currency, policy).normalised?.total }.getOrNull()
        }

        val categoryRows = categories.await().rows
        val currentTotals = current.await()
        SummaryUiState(
            loading = false,
            currency = currency,
            range = range,
            groupBy = groupBy,
            windowStart = start,
            hero = currentTotals?.let { totals ->
                Hero(
                    anchor = anchor.date,
                    lagging = anchor.lagging,
                    exact = anchor.exact,
                    spent = Money(abs(totals.spend.amount), currency),
                    previous = previous.await()?.let { Money(abs(it.spend.amount), currency) },
                    income = Money(abs(totals.income.amount), currency),
                )
            },
            ranked = Spending.rank(categoryRows, currency),
            months = Spending.monthlyFlows(monthRows, currency),
            unconvertible = Spending.unconvertible(categoryRows),
            stale = freshness.await()?.accounts?.filter { it.status == "stale" }?.sortedByDescending { it.daysOverdue }.orEmpty(),
        )
    }
}
