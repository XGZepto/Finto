package page.zepto.finto.feature.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryNormalised
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import page.zepto.finto.core.testing.TestData

class SummaryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun totals(spend: Long, income: Long) =
        SummaryNormalised(to = "HKD", total = FlowTotals(Money(income - spend, "HKD"), Money(spend, "HKD"), Money(income, "HKD")))

    private val ledger = FakeLedgerRepository { groupBy, filter, _ ->
        when {
            groupBy == "category" -> SummaryResponse(
                groupBy,
                rows = listOf(
                    TestData.row("groceries", 30_000),
                    TestData.row("dining", 50_000),
                    TestData.row("transfers", 999_999),
                    TestData.row("travel", 10_000, currency = "XAU", convertible = false),
                ),
            )
            groupBy == "kind" -> SummaryResponse(groupBy, ledgerThrough = "2020-03-15")
            // The month-to-anchor comparison: current month, then the month before.
            groupBy == "month" && filter.to == "2020-03-15" -> SummaryResponse(groupBy, normalised = totals(60_000, 200_000))
            groupBy == "month" && filter.to == "2020-02-15" -> SummaryResponse(groupBy, normalised = totals(40_000, 0))
            else -> SummaryResponse(groupBy, rows = listOf(TestData.row("2020-02", 40_000), TestData.row("2020-03", 60_000)))
        }
    }

    @Test
    fun heroFollowsTheLedgerAndComparesTheSameDays() {
        val viewModel = SummaryViewModel(ledger, FakeSessionRepository())
        val state = viewModel.state.value
        assertFalse(state.loading)
        val hero = assertNotNull(state.hero).let { state.hero!! }
        assertTrue(hero.lagging)
        assertEquals("2020-03-15", hero.anchor.toString())
        assertEquals(Money(60_000, "HKD"), hero.spent)
        assertEquals(50.0, hero.change!!, 1e-9)
        assertEquals(70.0, hero.savingsRate!!, 1e-9)
    }

    @Test
    fun rankingSkipsMovementAndReportsUnconvertible() {
        val state = SummaryViewModel(ledger, FakeSessionRepository()).state.value
        assertEquals(listOf("dining", "groceries"), state.ranked.map { it.bucket })
        assertEquals(listOf("XAU"), state.unconvertible)
        assertTrue(ledger.summaryCalls.filter { it.first == "category" }.all { it.second.spendingOnly && it.third == "HKD" })
    }

    @Test
    fun changingTheRangeReloadsFromTheNewWindow() {
        val viewModel = SummaryViewModel(ledger, FakeSessionRepository())
        viewModel.setRange(SummaryRange.Quarter)
        assertEquals(SummaryRange.Quarter, viewModel.state.value.range)
        val starts = ledger.summaryCalls.filter { it.first == "category" }.map { it.second.from }
        assertEquals(2, starts.distinct().size)
    }

    @Test
    fun reportingCurrencyComesFromTheUser() {
        val session = FakeSessionRepository()
        val viewModel = SummaryViewModel(ledger, session)
        kotlinx.coroutines.test.runTest { session.setBaseCurrency("USD") }
        assertEquals("USD", viewModel.state.value.currency)
        assertEquals("USD", ledger.summaryCalls.last { it.first == "category" }.third)
    }
}
