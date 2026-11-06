package page.zepto.finto.feature.investments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.Holding
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MpfDocument
import page.zepto.finto.core.model.ValuePoint
import java.util.Locale

class InvestmentsTest {

    private fun point(date: String, amount: Long) = ValuePoint(date, Money(amount, "HKD"))

    @Test
    fun friendlyNamesKeepAcronyms() {
        assertEquals("HSBC MPF", Investments.friendly("hsbc_mpf"))
        assertEquals("Regular contribution", Investments.friendly("regular_contribution"))
        assertEquals("HSBC MPF TDVC", Investments.friendly("hsbc-mpf-tdvc"))
        assertEquals("Member returns", Investments.friendly("member_returns"))
    }

    @Test
    fun allocationFractionsReadAsPercentages() {
        assertEquals("42.1%", Investments.percent("0.4210", Locale.UK))
        assertEquals("100.0%", Investments.percent("1", Locale.UK))
        assertEquals("rounds half up", "33.4%", Investments.percent("0.33350", Locale.UK))
        assertEquals("0.5%", Investments.percent("0.005", Locale.UK))
        assertNull(Investments.percent(null))
        assertNull(Investments.percent(" "))
        assertEquals("unparseable text is shown as it came", "n/a", Investments.percent("n/a"))
    }

    @Test
    fun unitsAreGroupedAndKeepTheirPrecision() {
        assertEquals("12,345.6789", Investments.units("12345.6789", Locale.UK))
        assertEquals("1,000", Investments.units("1000", Locale.UK))
        assertEquals("0.50", Investments.units("0.50", Locale.UK))
        assertNull(Investments.units(null))
    }

    @Test
    fun historyStopsAtTheValuationOnShow() {
        val points = listOf(point("2026-03-31", 100), point("2026-06-30", 110), point("2026-09-30", 120))
        assertEquals(2, Investments.visibleHistory(points, "2026-06-30").size)
        assertEquals(3, Investments.visibleHistory(points, null).size)
    }

    @Test
    fun changeIsTheLastPointAgainstTheOneBefore() {
        val change = Investments.change(listOf(point("2026-03-31", 100_000), point("2026-06-30", 90_000), point("2026-09-30", 99_000)))!!
        assertEquals(Money(9_000, "HKD"), change.amount)
        assertEquals(10.0, change.percent!!, 1e-9)
        assertEquals("2026-06-30", change.since)

        val down = Investments.change(listOf(point("2026-06-30", 100_000), point("2026-09-30", 95_000)))!!
        assertEquals(-5.0, down.percent!!, 1e-9)
    }

    @Test
    fun noChangeWithoutTwoPointsAndNoPercentFromZero() {
        assertNull(Investments.change(listOf(point("2026-09-30", 1))))
        assertNull(Investments.change(emptyList()))
        val fromZero = Investments.change(listOf(point("2026-06-30", 0), point("2026-09-30", 500)))!!
        assertEquals(Money(500, "HKD"), fromZero.amount)
        assertNull(fromZero.percent)
    }

    @Test
    fun barsScaleAgainstTheLargestHolding() {
        val holdings = listOf(
            Holding("Global Equity", marketValue = Money(200_000, "HKD")),
            Holding("Bond", marketValue = Money(50_000, "HKD")),
        )
        assertEquals(1f, Investments.barFraction(holdings[0], holdings), 1e-6f)
        assertEquals(0.25f, Investments.barFraction(holdings[1], holdings), 1e-6f)
        val empty = Holding("Cash", marketValue = Money(0, "HKD"))
        assertEquals(0f, Investments.barFraction(empty, listOf(empty)), 1e-6f)
    }

    @Test
    fun signedPercentToOneDecimal() {
        assertEquals("+1.2%", Investments.signedPercent(1.234))
        assertEquals("-0.4%", Investments.signedPercent(-0.44))
        assertEquals("+0.0%", Investments.signedPercent(0.0))
    }

    @Test
    fun chartPositionsFollowDatesAndValues() {
        val positions = chartPositions(listOf(point("2026-01-01", 100), point("2026-01-11", 200), point("2026-01-31", 150)))
        assertEquals(0f, positions[0].x, 1e-6f)
        assertEquals(1f / 3f, positions[1].x, 1e-6f)
        assertEquals(1f, positions[2].x, 1e-6f)
        assertTrue("the highest value is nearest the top", positions[1].y < positions[2].y && positions[2].y < positions[0].y)
        assertTrue(positions.all { it.y in 0f..1f })
    }

    @Test
    fun aFlatHistorySitsInTheMiddle() {
        val positions = chartPositions(listOf(point("2026-01-01", 100), point("2026-02-01", 100)))
        assertEquals(0.5f, positions[0].y, 1e-6f)
        assertEquals(0.5f, positions[1].y, 1e-6f)
    }

    @Test
    fun parserLabelsSayHowADocumentWasRead() {
        assertEquals("Template", parserLabel(MpfDocument("a.pdf", parser = "deterministic")))
        assertEquals("AI extracted · claude-sonnet", parserLabel(MpfDocument("a.pdf", parser = "llm", model = "claude-sonnet")))
        assertEquals("AI extracted", parserLabel(MpfDocument("a.pdf", parser = "llm")))
        assertEquals("", parserLabel(MpfDocument("a.pdf")))
    }
}
