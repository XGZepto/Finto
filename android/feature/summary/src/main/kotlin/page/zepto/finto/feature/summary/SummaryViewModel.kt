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
import page.zepto.finto.core.data.ApiErrors
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
    /** Change against the same days of the month before, in percent. */
    val change: Double?
        get() = previous?.takeIf { it.amount != 0L }?.let { (spent.amount - it.amount) * 100.0 / it.amount }

    val savingsRate: Double?
        get() = income.amount.takeIf { it > 0 }?.let { (it - spent.amount) * 100.0 / it }
}

data class SummaryUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = "HKD",
    val range: SummaryRange = SummaryRange.Year,
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
    session: SessionRepository,
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

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean = false) {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = !refreshing && it.hero == null, refreshing = refreshing, error = null) }
        loadJob = viewModelScope.launch {
            val currency = mutableState.value.currency
            val range = mutableState.value.range
            suspendRunCatching { fetch(currency, range) }
                .onSuccess { loaded -> mutableState.value = loaded.copy(loading = false, refreshing = false) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(loading = false, refreshing = false, error = ApiErrors.message(error))
                    }
                }
        }
    }

    private suspend fun fetch(currency: String, range: SummaryRange): SummaryUiState = coroutineScope {
        val today = LocalDate.now(clock)
        val start = HeroAnchor.windowStart(range.months, today).toString()
        // Spending only: a card payment the linker has not paired is still money changing pockets.
        val period = LedgerFilter(from = start, spendingOnly = true)
        val categories = async { ledger.summary("category", period, currency) }
        val months = async { ledger.summary("month", period, currency) }
        // `from` today returns no rows; the API reports ledger_through regardless of the dates.
        val probe = async {
            suspendRunCatching {
                ledger.summary("kind", LedgerFilter(from = today.toString(), to = today.toString(), spendingOnly = true, maxAmount = -1))
            }.getOrNull()
        }
        val freshness = async { suspendRunCatching { ledger.statementFreshness() }.getOrNull() }

        val monthRows = months.await().rows
        val anchor = HeroAnchor.anchor(probe.await()?.ledgerThrough, monthRows, today)
        val (currentFilter, previousFilter) = HeroAnchor.comparison(anchor.date)
        val current = async { ledger.summary("month", currentFilter, currency).normalised?.total }
        val previous = async { suspendRunCatching { ledger.summary("month", previousFilter, currency).normalised?.total }.getOrNull() }

        val categoryRows = categories.await().rows
        val currentTotals = current.await()
        SummaryUiState(
            loading = false,
            currency = currency,
            range = range,
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
            stale = freshness.await()?.accounts?.filter { it.status == "stale" }.orEmpty(),
        )
    }
}
