package page.zepto.finto.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The same cases as the web app's hero-anchor.spec.ts. */
class HeroAnchorTest {
    private val today = LocalDate.of(2026, 11, 2)

    private fun month(bucket: String, count: Int, spend: Long = 100) = SummaryRow(
        bucket = bucket, currency = "HKD", txnCount = count,
        net = Money(-spend, "HKD"), spend = Money(spend, "HKD"), income = Money(0, "HKD"),
    )

    @Test
    fun ledgerStoppingLastMonthAnchorsOnItsFinalDay() {
        val anchor = HeroAnchor.anchor("2026-10-23", emptyList(), today)
        assertTrue(anchor.lagging)
        assertEquals(LocalDate.of(2026, 10, 23), anchor.date)
    }

    @Test
    fun ledgerCurrentToTodayKeepsTheWallClock() {
        assertFalse(HeroAnchor.anchor("2026-11-02", emptyList(), today).lagging)
        assertEquals(today, HeroAnchor.anchor("2026-11-05", emptyList(), today).date)
    }

    @Test
    fun withoutLedgerThroughTheLastMonthWithRowsStandsIn() {
        val anchor = HeroAnchor.anchor(
            null,
            listOf(month("2026-09", 4), month("2026-10", 2), month("2026-11", 0)),
            today,
        )
        assertTrue(anchor.lagging)
        assertFalse(anchor.exact)
        assertEquals(LocalDate.of(2026, 10, 31), anchor.date)
    }

    @Test
    fun noDataFallsBackToToday() {
        assertFalse(HeroAnchor.anchor(null, emptyList(), today).lagging)
    }

    @Test
    fun monthHoldingOnlyIncomeDoesNotCountAsCurrent() {
        val anchor = HeroAnchor.anchor(
            null, listOf(month("2026-10", 9, spend = 500), month("2026-11", 1, spend = 0)), today,
        )
        assertTrue(anchor.lagging)
        assertEquals(10, anchor.date.monthValue)
    }

    @Test
    fun comparisonCoversTheSameSpanOfThePreviousMonth() {
        val (current, previous) = HeroAnchor.comparison(LocalDate.of(2026, 3, 31))
        assertEquals("2026-03-01", current.from)
        assertEquals("2026-03-31", current.to)
        assertEquals("2026-02-01", previous.from)
        assertEquals("2026-02-28", previous.to)
        assertTrue(current.spendingOnly && previous.spendingOnly)
    }

    @Test
    fun windowStartCountsThisMonth() {
        assertEquals(LocalDate.of(2025, 12, 1), HeroAnchor.windowStart(12, today))
    }
}
