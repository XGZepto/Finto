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

class DrillTest {
    @Test
    fun entityDimensionsBecomeListFilters() {
        val scope = LedgerFilter(from = "2026-01-01", spendingOnly = true)
        assertEquals(listOf("dining"), Drill.into("category", "dining", scope).categories)
        assertEquals("2026-01-01", Drill.into("category", "dining", scope).from)
        assertEquals(listOf("amex"), Drill.into("account", "amex").accounts)
        assertEquals(listOf("subscription"), Drill.into("tag", "subscription").tags)
    }

    @Test
    fun monthsBecomeRangesAndMerchantsBecomeSearch() {
        val month = Drill.into("month", "2026-02", LedgerFilter(months = listOf("2026-01")))
        assertEquals("2026-02-01" to "2026-02-28", month.from to month.to)
        assertTrue(month.months.isEmpty())
        assertEquals("Adobe", Drill.into("merchant", "Adobe").query)
    }

    @Test
    fun filterTravelsAsJson() {
        val filter = LedgerFilter(accounts = listOf("a"), spendingOnly = true, query = "café")
        val decoded = FintoJson.decodeFromString(LedgerFilter.serializer(), FintoJson.encodeToString(LedgerFilter.serializer(), filter))
        assertEquals(filter, decoded)
    }
}
