package page.zepto.finto.feature.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.CompositionSeries
import page.zepto.finto.core.model.CoverageAccount
import page.zepto.finto.core.model.LedgerFilter

class TimelineMathTest {

    private val composition = Composition(
        dimension = "category",
        currency = "HKD",
        months = listOf("2026-09", "2026-10", "2026-11"),
        series = listOf(
            CompositionSeries("dining", total = 900, values = listOf(300, 600, 0)),
            CompositionSeries("travel", total = 300, values = listOf(100, 200, 0)),
            CompositionSeries("other", total = 200, values = listOf(0, 200, 0)),
        ),
    )

    @Test
    fun totalsAddEverySeriesInAMonth() {
        assertEquals(listOf(400L, 1_000L, 0L), TimelineMath.monthTotals(composition))
    }

    @Test
    fun shareFillsEachColumnFromTheBottomInSeriesOrder() {
        val columns = TimelineMath.columns(composition, TimelineScale.Share)
        val september = columns[0].segments
        assertEquals(listOf(0.75f, 0.25f, 0f), september.map { it.height })
        assertEquals(listOf(0f, 0.75f, 1f), september.map { it.bottom })
        assertEquals(1f, columns[1].segments.sumOf { it.height.toDouble() }.toFloat(), 1e-6f)
        // An empty month divides by one, not zero.
        assertEquals(listOf(0f, 0f, 0f), columns[2].segments.map { it.height })
        assertEquals(listOf(0, 1, 2), september.map { it.series })
    }

    @Test
    fun amountScalesEveryColumnToThePeakMonth() {
        val columns = TimelineMath.columns(composition, TimelineScale.Amount)
        assertEquals(0.4f, columns[0].segments.sumOf { it.height.toDouble() }.toFloat(), 1e-6f)
        assertEquals(1f, columns[1].segments.sumOf { it.height.toDouble() }.toFloat(), 1e-6f)
        assertEquals(0.3f, columns[0].segments[0].height, 1e-6f)
        assertEquals(1_000L, columns[1].total)
    }

    @Test
    fun aTapLandsOnTheBandUnderIt() {
        val october = TimelineMath.columns(composition, TimelineScale.Share)[1]
        assertEquals("dining", TimelineMath.segmentAt(october, 0.1f)?.bucket)
        assertEquals("travel", TimelineMath.segmentAt(october, 0.7f)?.bucket)
        assertEquals("other", TimelineMath.segmentAt(october, 0.95f)?.bucket)
        val amount = TimelineMath.columns(composition, TimelineScale.Amount)[0]
        assertNull(TimelineMath.segmentAt(amount, 0.9f))
    }

    @Test
    fun drillCarriesTheBucketAndItsMonth() {
        assertEquals(
            LedgerFilter(from = "2026-09-01", to = "2026-09-30", spendingOnly = true, categories = listOf("dining")),
            TimelineMath.drill("category", "dining", "2026-09"),
        )
        assertEquals(
            LedgerFilter(from = "2026-02-01", to = "2026-02-28", spendingOnly = true, query = "Wellcome"),
            TimelineMath.drill("merchant", "Wellcome", "2026-02"),
        )
        assertEquals(listOf("You"), TimelineMath.drill("cardholder", "You", "2026-02").cardholders)
    }

    @Test
    fun coverageCountsLeaveOutZeroes() {
        val account = CoverageAccount("mox", "Mox", statementMonths = 12, exportMonths = 0, gapMonths = 1)
        assertEquals("12 statement · 1 missing", TimelineMath.coverageCounts(account))
        assertEquals("19 export", TimelineMath.coverageCounts(account.copy(statementMonths = 0, exportMonths = 19, gapMonths = 0)))
        assertEquals("", TimelineMath.coverageCounts(account.copy(statementMonths = 0, gapMonths = 0)))
    }
}
