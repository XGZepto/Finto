package page.zepto.finto.feature.reports

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryNormalised
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import page.zepto.finto.core.testing.TestData
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ReportsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC)

    private fun normalised(spend: Long, income: Long) = SummaryNormalised(
        to = "HKD",
        total = FlowTotals(net = Money(income - spend, "HKD"), spend = Money(spend, "HKD"), income = Money(income, "HKD")),
    )

    /** Fails the split read while true, as a dropped connection would. */
    private var offline = false

    private val ledger = FakeLedgerRepository { groupBy, filter, _ ->
        when (groupBy) {
            "month" -> SummaryResponse(
                groupBy,
                rows = listOf(TestData.row("2026-09", 40_000), TestData.row("2026-10", 60_000), TestData.row("2026-11", 5_000)),
            )
            "kind" -> SummaryResponse(groupBy, normalised = normalised(spend = 80_000, income = 150_000))
            else -> {
                if (offline) throw IOException("offline")
                SummaryResponse(
                    groupBy,
                    rows = listOf(TestData.row("dining", 60_000), TestData.row("groceries", 40_000), TestData.row("salary", 0, income = 200_000)),
                    normalised = normalised(spend = if (filter.months.isEmpty()) 100_000 else 40_000, income = 200_000),
                )
            }
        }
    }.apply {
        accounts = listOf(
            Account(id = "hsbc_current", institutionId = "hsbc", displayName = "HSBC Current", accountType = "current", primaryCurrency = "HKD"),
        )
    }

    private fun viewModel(session: FakeSessionRepository = FakeSessionRepository()) = ReportsViewModel(ledger, session, clock)

    private fun lastCall(groupBy: String): LedgerFilter = ledger.summaryCalls.last { it.first == groupBy }.second

    @Test
    fun loadsTheThreeMonthReportInTheBaseCurrency() {
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertNull(state.error)
        val report = assertNotNull(state.report).let { state.report!! }
        assertEquals(Money(100_000, "HKD"), report.totals!!.spend)
        assertEquals(listOf("dining", "groceries"), report.bands.map { it.bucket })
        assertEquals(listOf("2026-09", "2026-10", "2026-11"), report.months.map { it.month })
        assertEquals(50_000L, report.mean)
        assertEquals(25.0, report.delta!!.percent, 1e-9)
        assertEquals(50.0, report.savingsRate!!, 1e-9)
        assertEquals("vs prior 3 months", state.comparisonLabel)
        assertEquals(DateWindow(LocalDate.parse("2026-08-06"), LocalDate.parse("2026-11-06")), state.window)

        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), lastCall("category"))
        assertEquals(LedgerFilter(from = "2026-05-05", to = "2026-08-05", spendingOnly = true), lastCall("kind"))
        assertTrue(ledger.summaryCalls.all { it.third == "HKD" })
        assertEquals(listOf("hsbc_current"), state.accounts.map { it.id })
    }

    @Test
    fun selectingAMonthNarrowsTheSplitAndComparesWithTheMonthBefore() {
        val viewModel = viewModel()
        viewModel.toggleMonth("2026-10")
        val state = viewModel.state.value
        assertEquals(listOf("2026-10"), state.selectedMonths)
        assertEquals("vs prior month", state.comparisonLabel)
        assertEquals(LedgerFilter(months = listOf("2026-10"), spendingOnly = true), lastCall("category"))
        assertEquals(LedgerFilter(from = "2026-09-01", to = "2026-09-30", spendingOnly = true), lastCall("kind"))
        // The chart keeps the whole window, so other months stay tappable.
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), lastCall("month"))
        assertEquals(Money(40_000, "HKD"), state.report!!.totals!!.spend)
        assertEquals(LedgerFilter(months = listOf("2026-10"), spendingOnly = true, categories = listOf("dining")), state.report!!.drill("dining"))
    }

    @Test
    fun twoSelectedMonthsHaveNoComparison() {
        val viewModel = viewModel()
        viewModel.toggleMonth("2026-10")
        viewModel.toggleMonth("2026-09")
        val kindCalls = ledger.summaryCalls.count { it.first == "kind" }
        assertEquals(listOf("2026-09", "2026-10"), viewModel.state.value.selectedMonths)
        assertNull(viewModel.state.value.report!!.prior)
        assertNull(viewModel.state.value.report!!.delta)
        viewModel.toggleMonth("2026-09")
        assertTrue(ledger.summaryCalls.count { it.first == "kind" } > kindCalls)
        viewModel.clearMonths()
        assertTrue(viewModel.state.value.selectedMonths.isEmpty())
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), lastCall("category"))
    }

    @Test
    fun changingThePeriodClearsTheSelection() {
        val viewModel = viewModel()
        viewModel.toggleMonth("2026-10")
        viewModel.setPeriod(ReportPeriod.YearToDate)
        val state = viewModel.state.value
        assertEquals(ReportPeriod.YearToDate, state.period)
        assertTrue(state.selectedMonths.isEmpty())
        assertEquals(LedgerFilter(from = "2026-01-01", to = "2026-11-06", spendingOnly = true), lastCall("category"))
        assertEquals("vs prior period", state.comparisonLabel)
    }

    @Test
    fun oneAccountDropsTheSpendingScope() {
        val viewModel = viewModel()
        viewModel.setAccount("hsbc_current")
        assertEquals("HSBC Current", viewModel.state.value.account?.displayName)
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", accounts = listOf("hsbc_current")), lastCall("category"))
        viewModel.setAccount(null)
        assertTrue(lastCall("category").spendingOnly)
    }

    @Test
    fun theSplitDecidesTheGroupingAndTheDrill() {
        val viewModel = viewModel()
        viewModel.setSplit("merchant")
        assertEquals(LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true), lastCall("merchant"))
        val report = viewModel.state.value.report!!
        assertEquals("merchant", report.split)
        assertEquals("dining", report.drill("dining").query)
    }

    @Test
    fun aFailureWithNothingShownIsAnError() {
        offline = true
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertNull(state.report)
        assertEquals("Can't reach the server. Check your connection and try again.", state.error)
    }

    @Test
    fun aFailedReloadKeepsTheReportAndReportsTheError() = runTest {
        val viewModel = viewModel()
        viewModel.state.test {
            assertNotNull(awaitItem().report)
            offline = true
            viewModel.refresh()
            var failed = awaitItem()
            while (failed.error == null) failed = awaitItem()
            assertNotNull(failed.report)
            assertFalse(failed.refreshing)
            viewModel.errorShown()
            assertNull(awaitItem().error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshReadsAgainAndRecovers() {
        offline = true
        val viewModel = viewModel()
        assertNull(viewModel.state.value.report)
        offline = false
        viewModel.refresh()
        assertNotNull(viewModel.state.value.report)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun withoutAccountsTheSelectorOffersAllAccountsOnly() {
        ledger.failure = IOException("offline")
        val state = viewModel().state.value
        assertTrue(state.accounts.isEmpty())
        assertNotNull(state.report)
    }

    @Test
    fun followsTheReportingCurrency() = runTest {
        val session = FakeSessionRepository()
        val viewModel = viewModel(session)
        session.setBaseCurrency("USD")
        assertEquals("USD", viewModel.state.value.currency)
        assertEquals("USD", ledger.summaryCalls.last().third)
    }
}
