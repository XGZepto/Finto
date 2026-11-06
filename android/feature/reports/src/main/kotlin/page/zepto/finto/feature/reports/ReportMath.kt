package page.zepto.finto.feature.reports

import page.zepto.finto.core.model.Drill
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryRow
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** The period pills, in the web's order. [months] is null for year to date. */
enum class ReportPeriod(val label: String, val months: Int?, val comparison: String) {
    OneMonth("1M", 1, "vs prior month"),
    ThreeMonths("3M", 3, "vs prior 3 months"),
    SixMonths("6M", 6, "vs prior 6 months"),
    OneYear("1Y", 12, "vs prior year"),
    YearToDate("YTD", null, "vs prior period"),
}

/** An inclusive date range. */
data class DateWindow(val from: LocalDate, val to: LocalDate)

/** The three reads behind one report: the split, the comparison (when there is one), and the months. */
data class ReportRequests(
    val main: LedgerFilter,
    val prior: LedgerFilter?,
    val months: LedgerFilter,
)

/** Change in outflow against the comparison window: [amount] above zero means more went out. */
data class SpendDelta(val amount: Money, val percent: Double)

/** One bucket of "where the money went": what it paid out net of what it took in. */
data class Band(
    val bucket: String,
    /** Positive: money out less money in, in the reporting currency. */
    val amount: Money,
    /** Of all bands, 0..1. */
    val share: Double,
    /** Of the largest band, 0.01..1, for the bar. */
    val fraction: Double,
)

/** Outflow in one month, in minor units of the reporting currency. */
data class MonthSpend(val month: String, val spend: Long)

/**
 * The report's arithmetic, kept apart from the view model so each rule can be
 * checked against the web's Reports page, which it mirrors.
 */
object ReportMath {

    val SPLITS = listOf("category", "subcategory", "tag", "merchant", "cardholder", "account", "kind")
    const val DEFAULT_SPLIT = "category"
    const val TOP_BANDS = 12

    /**
     * The selected window ending [today], and the one of equal length that ends
     * the day before it starts. The start steps back by calendar months the way
     * the web's `Date.setMonth` does: the day of the month carries over and
     * overflows, so 31 May less three months is 3 March, not 28 February.
     */
    fun windows(period: ReportPeriod, today: LocalDate): Pair<DateWindow, DateWindow> {
        val from = when (val months = period.months) {
            null -> today.withDayOfYear(1)
            else -> today.withDayOfMonth(1).minusMonths(months.toLong()).plusDays(today.dayOfMonth - 1L)
        }
        val span = ChronoUnit.DAYS.between(from, today)
        val priorTo = from.minusDays(1)
        return DateWindow(from, today) to DateWindow(priorTo.minusDays(span), priorTo)
    }

    /** First to last day of a `YYYY-MM` month. */
    fun monthWindow(month: String): DateWindow =
        YearMonth.parse(month).let { DateWindow(it.atDay(1), it.atEndOfMonth()) }

    /** The calendar month before a `YYYY-MM` month. */
    fun priorMonthWindow(month: String): DateWindow =
        monthWindow(YearMonth.parse(month).minusMonths(1).toString())

    /**
     * The ledger filter for a figure. Selected months replace the date range.
     * Across all accounts, card payments and transfers are money changing
     * pockets, not spending (the scope Summary uses, so the two agree); scoped
     * to one account, money crossing its edge is the point and stays in.
     */
    fun scope(range: DateWindow?, months: List<String>, accountId: String?): LedgerFilter {
        val base = if (months.isNotEmpty()) {
            LedgerFilter(months = months)
        } else {
            LedgerFilter(from = range?.from?.toString(), to = range?.to?.toString())
        }
        return if (accountId != null) base.copy(accounts = listOf(accountId)) else base.copy(spendingOnly = true)
    }

    /**
     * No months selected compares with the window before; one month with the
     * calendar month before it; several months with nothing, since they need not
     * be contiguous. The month chart always spans the whole window.
     */
    fun requests(period: ReportPeriod, selected: List<String>, accountId: String?, today: LocalDate): ReportRequests {
        val (current, prior) = windows(period, today)
        val comparison = when (selected.size) {
            0 -> prior
            1 -> priorMonthWindow(selected.single())
            else -> null
        }
        return ReportRequests(
            main = scope(current, selected, accountId),
            prior = comparison?.let { scope(it, emptyList(), accountId) },
            months = scope(current, emptyList(), accountId),
        )
    }

    fun comparisonLabel(period: ReportPeriod, selectedCount: Int): String =
        if (selectedCount == 1) "vs prior month" else period.comparison

    fun spendDelta(now: FlowTotals?, before: FlowTotals?): SpendDelta? {
        if (now == null || before == null || before.spend.amount == 0L) return null
        val diff = abs(now.spend.amount) - abs(before.spend.amount)
        return SpendDelta(Money(diff, now.spend.currency), diff * 100.0 / abs(before.spend.amount))
    }

    /** Share of income not spent, in percent; null without income. */
    fun savingsRate(totals: FlowTotals?): Double? {
        val income = totals?.income?.amount ?: return null
        if (income <= 0) return null
        return (income - abs(totals.spend.amount)) * 100.0 / income
    }

    /**
     * Net outflow per bucket, largest first. A bucket that took in at least as
     * much as it paid out (income, rewards, a refunded category) is not
     * somewhere money went, so it is left out. Rows without a rate are skipped.
     */
    fun bands(rows: List<SummaryRow>, currency: String): List<Band> {
        val flows = linkedMapOf<String, LongArray>()
        for (row in rows) {
            val spend = row.spendConverted?.takeIf { it.ok } ?: continue
            val income = row.incomeConverted?.takeIf { it.ok }?.amount ?: 0
            val acc = flows.getOrPut(row.bucket) { LongArray(2) }
            acc[0] += abs(spend.amount)
            acc[1] += abs(income)
        }
        val net = flows.mapNotNull { (bucket, acc) -> if (acc[0] <= acc[1]) null else bucket to acc[0] - acc[1] }
        val all = net.sumOf { it.second }.coerceAtLeast(1)
        val peak = (net.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        return net.sortedByDescending { it.second }.map { (bucket, amount) ->
            Band(
                bucket = bucket,
                amount = Money(amount, currency),
                share = amount.toDouble() / all,
                fraction = (amount.toDouble() / peak).coerceAtLeast(0.01),
            )
        }
    }

    /** Outflow per month across currencies, oldest first. */
    fun monthlySpend(rows: List<SummaryRow>): List<MonthSpend> {
        val byMonth = sortedMapOf<String, Long>()
        for (row in rows) {
            val spend = row.spendConverted?.takeIf { it.ok } ?: continue
            byMonth[row.bucket] = (byMonth[row.bucket] ?: 0) + abs(spend.amount)
        }
        return byMonth.map { (month, spend) -> MonthSpend(month, spend) }
    }

    /**
     * Mean outflow of complete months. The month in progress is left out: a
     * few days of it would drag the mean down.
     */
    fun monthlyMean(months: List<MonthSpend>, current: YearMonth): Long? {
        val complete = months.filter { it.month != current.toString() }
        if (complete.isEmpty()) return null
        return (complete.sumOf { it.spend }.toDouble() / complete.size).roundToLong()
    }

    /** Tapping a month adds it to the selection, or takes it out; the selection stays in order. */
    fun toggle(selected: List<String>, month: String): List<String> =
        if (month in selected) selected - month else (selected + month).sorted()

    /** The blotter, scoped to the same window and accounts the figure came from. */
    fun drill(split: String, bucket: String, scope: LedgerFilter): LedgerFilter = Drill.into(split, bucket, scope)
}
