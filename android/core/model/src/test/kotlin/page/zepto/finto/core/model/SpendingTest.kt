package page.zepto.finto.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingTest {
    @Test
    fun ranksConvertedSpendAndSkipsMovement() {
        val rows = Fixtures.decode<SummaryResponse>("summary_category").rows
        val ranked = Spending.rank(rows, "HKD")
        assertTrue(ranked.isNotEmpty())
        assertFalse(ranked.any { Spending.isMovement(it.bucket) })
        assertEquals(ranked.sortedByDescending { it.spend.amount }, ranked)
        assertEquals(1.0, ranked.sumOf { it.share }, 1e-9)
    }

    @Test
    fun monthlyFlowsAreChronological() {
        val rows = Fixtures.decode<SummaryResponse>("summary_month").rows
        val months = Spending.monthlyFlows(rows, "HKD").map { it.month }
        assertEquals(months.sorted(), months)
    }

    @Test
    fun labels() {
        assertEquals("Credit card", Labels.humanize("credit_card"))
        assertEquals("Uncategorised", Labels.humanize("(uncategorised)"))
        assertEquals("HSBC current", Labels.humanize("hsbc_current"))
        assertEquals("Card payment", Labels.kind("cc_payment"))
    }
}
