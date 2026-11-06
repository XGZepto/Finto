package page.zepto.finto.feature.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.testing.TestData
import java.time.LocalDate
import java.time.YearMonth

class ReportMathTest {

    private val today = LocalDate.parse("2026-11-06")

    private fun window(from: String, to: String) = DateWindow(LocalDate.parse(from), LocalDate.parse(to))

    private fun totals(spend: Long, income: Long) =
        FlowTotals(net = Money(income - spend, "HKD"), spend = Money(spend, "HKD"), income = Money(income, "HKD"))

    @Test
    fun threeMonthsEndsTodayAndThePriorWindowIsAsLongAndEndsTheDayBefore() {
        val (current, prior) = ReportMath.windows(ReportPeriod.ThreeMonths, today)
        assertEquals(window("2026-08-06", "2026-11-06"), current)
        // 92 days back from the day before the window starts.
        assertEquals(window("2026-05-05", "2026-08-05"), prior)
    }

    @Test
    fun stepsBackByMonthsAsTheWebsSetMonthDoes() {
        // 31 May less three months is "31 February", which rolls over to 3 March.
        assertEquals(LocalDate.parse("2026-03-03"), ReportMath.windows(ReportPeriod.ThreeMonths, LocalDate.parse("2026-05-31")).first.from)
        assertEquals(LocalDate.parse("2026-03-03"), ReportMath.windows(ReportPeriod.OneMonth, LocalDate.parse("2026-03-31")).first.from)
        assertEquals(LocalDate.parse("2025-11-06"), ReportMath.windows(ReportPeriod.OneYear, today).first.from)
        assertEquals(LocalDate.parse("2026-05-06"), ReportMath.windows(ReportPeriod.SixMonths, today).first.from)
    }

    @Test
    fun yearToDateStartsOnTheFirstOfJanuary() {
        val (current, prior) = ReportMath.windows(ReportPeriod.YearToDate, today)
        assertEquals(window("2026-01-01", "2026-11-06"), current)
        assertEquals(window("2025-02-25", "2025-12-31"), prior)
    }

    @Test
    fun monthWindowsCoverWholeCalendarMonths() {
        assertEquals(window("2026-02-01", "2026-02-28"), ReportMath.monthWindow("2026-02"))
        assertEquals(window("2024-02-01", "2024-02-29"), ReportMath.monthWindow("2024-02"))
        assertEquals(window("2025-12-01", "2025-12-31"), ReportMath.priorMonthWindow("2026-01"))
    }

    @Test
    fun selectedMonthsReplaceTheDateRange() {
        val range = window("2026-08-06", "2026-11-06")
        assertEquals(
            LedgerFilter(months = listOf("2026-09", "2026-10"), spendingOnly = true),
            ReportMath.scope(range, listOf("2026-09", "2026-10"), accountId = null),
        )
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), ReportMath.scope(range, emptyList(), null))
    }

    @Test
    fun oneAccountKeepsMoneyCrossingItsEdge() {
        val filter = ReportMath.scope(window("2026-08-06", "2026-11-06"), emptyList(), "hsbc_current")
        assertEquals(listOf("hsbc_current"), filter.accounts)
        assertFalse(filter.spendingOnly)
    }

    @Test
    fun noSelectionComparesWithThePriorWindow() {
        val requests = ReportMath.requests(ReportPeriod.ThreeMonths, emptyList(), null, today)
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), requests.main)
        assertEquals(LedgerFilter(from = "2026-05-05", to = "2026-08-05", spendingOnly = true), requests.prior)
        assertEquals(requests.main, requests.months)
    }

    @Test
    fun oneSelectedMonthComparesWithTheMonthBefore() {
        val requests = ReportMath.requests(ReportPeriod.ThreeMonths, listOf("2026-09"), "mox", today)
        assertEquals(LedgerFilter(months = listOf("2026-09"), accounts = listOf("mox")), requests.main)
        assertEquals(LedgerFilter(from = "2026-08-01", to = "2026-08-31", accounts = listOf("mox")), requests.prior)
        // The chart still spans the whole window, whatever is selected.
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", accounts = listOf("mox")), requests.months)
    }

    @Test
    fun severalSelectedMonthsHaveNoComparison() {
        assertNull(ReportMath.requests(ReportPeriod.ThreeMonths, listOf("2026-09", "2026-10"), null, today).prior)
    }

    @Test
    fun comparisonIsLabelledByWhatItComparesWith() {
        assertEquals("vs prior 3 months", ReportMath.comparisonLabel(ReportPeriod.ThreeMonths, 0))
        assertEquals("vs prior month", ReportMath.comparisonLabel(ReportPeriod.OneMonth, 0))
        assertEquals("vs prior year", ReportMath.comparisonLabel(ReportPeriod.OneYear, 0))
        assertEquals("vs prior month", ReportMath.comparisonLabel(ReportPeriod.OneYear, 1))
    }

    @Test
    fun spendDeltaIsTheChangeInOutflow() {
        val delta = ReportMath.spendDelta(totals(spend = 120_00, income = 0), totals(spend = 100_00, income = 0))!!
        assertEquals(Money(20_00, "HKD"), delta.amount)
        assertEquals(20.0, delta.percent, 1e-9)
        assertEquals(-50.0, ReportMath.spendDelta(totals(50, 0), totals(100, 0))!!.percent, 1e-9)
        assertNull(ReportMath.spendDelta(totals(50, 0), totals(0, 10)))
        assertNull(ReportMath.spendDelta(totals(50, 0), null))
    }

    @Test
    fun savingsRateNeedsIncome() {
        assertEquals(25.0, ReportMath.savingsRate(totals(spend = 75, income = 100))!!, 1e-9)
        assertEquals(-50.0, ReportMath.savingsRate(totals(spend = 150, income = 100))!!, 1e-9)
        assertNull(ReportMath.savingsRate(totals(spend = 75, income = 0)))
        assertNull(ReportMath.savingsRate(null))
    }

    @Test
    fun bandsAreNetOutflowAndDropBucketsThatTookInAsMuch() {
        val bands = ReportMath.bands(
            listOf(
                TestData.row("dining", 30_000),
                TestData.row("groceries", 20_000),
                TestData.row("groceries", 15_000, currency = "USD"),
                TestData.row("shopping", 40_000, income = 10_000),
                TestData.row("salary", 0, income = 500_000),
                TestData.row("refunded", 5_000, income = 5_000),
                TestData.row("gold", 99_999, currency = "XAU", convertible = false),
            ),
            "HKD",
        )
        assertEquals(listOf("groceries", "dining", "shopping"), bands.map { it.bucket })
        assertEquals(listOf(35_000L, 30_000L, 30_000L), bands.map { it.amount.amount })
        assertEquals(35_000.0 / 95_000, bands[0].share, 1e-9)
        assertEquals(1.0, bands[0].fraction, 1e-9)
        assertEquals(30_000.0 / 35_000, bands[1].fraction, 1e-9)
        assertEquals(1.0, bands.sumOf { it.share }, 1e-9)
    }

    @Test
    fun aTinyBandStillShowsABar() {
        val bands = ReportMath.bands(listOf(TestData.row("rent", 1_000_000), TestData.row("stamps", 1)), "HKD")
        assertEquals(0.01, bands.last().fraction, 1e-9)
    }

    @Test
    fun monthlySpendSumsCurrenciesAndSkipsRowsWithoutARate() {
        val months = ReportMath.monthlySpend(
            listOf(
                TestData.row("2026-10", 50_000),
                TestData.row("2026-09", 30_000),
                TestData.row("2026-09", 10_000, currency = "USD"),
                TestData.row("2026-08", 7_000, currency = "XAU", convertible = false),
            ),
        )
        assertEquals(listOf(MonthSpend("2026-09", 40_000), MonthSpend("2026-10", 50_000)), months)
    }

    @Test
    fun meanLeavesOutTheMonthInProgress() {
        val months = listOf(MonthSpend("2026-09", 40_000), MonthSpend("2026-10", 50_001), MonthSpend("2026-11", 1_000))
        assertEquals(45_001L, ReportMath.monthlyMean(months, YearMonth.parse("2026-11")))
        assertEquals(30_334L, ReportMath.monthlyMean(months, YearMonth.parse("2026-12")))
        assertNull(ReportMath.monthlyMean(listOf(MonthSpend("2026-11", 1_000)), YearMonth.parse("2026-11")))
    }

    @Test
    fun togglingAMonthAddsItInOrderOrTakesItOut() {
        assertEquals(listOf("2026-08", "2026-10"), ReportMath.toggle(listOf("2026-10"), "2026-08"))
        assertEquals(listOf("2026-10"), ReportMath.toggle(listOf("2026-08", "2026-10"), "2026-08"))
        assertTrue(ReportMath.toggle(listOf("2026-08"), "2026-08").isEmpty())
    }

    @Test
    fun drillKeepsTheReportsScope() {
        val scope = LedgerFilter(months = listOf("2026-09"), spendingOnly = true)
        assertEquals(scope.copy(categories = listOf("dining")), ReportMath.drill("category", "dining", scope))
        assertEquals(scope.copy(tags = listOf("travel")), ReportMath.drill("tag", "travel", scope))
        assertEquals(scope.copy(query = "Wellcome"), ReportMath.drill("merchant", "Wellcome", scope))
        assertEquals(scope.copy(accounts = listOf("mox")), ReportMath.drill("account", "mox", scope))
    }
}
