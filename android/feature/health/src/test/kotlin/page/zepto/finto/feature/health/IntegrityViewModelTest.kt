package page.zepto.finto.feature.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.IntegritySummary
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class IntegrityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun check(account: String, status: String) = BalanceCheck(
        accountId = account, periodStart = "2026-10-01", periodEnd = "2026-10-31",
        expectedDelta = Money(100, "HKD"), actualDelta = Money(100, "HKD"), discrepancy = Money(0, "HKD"), status = status,
    )

    private val clean = IntegrityReport(
        healthy = true,
        balanceChecks = listOf(check("a", "ok"), check("b", "ok")),
        summary = IntegritySummary(checksRun = 2),
    )

    private val broken = IntegrityReport(
        healthy = false,
        balanceChecks = listOf(check("a", "ok"), check("b", "discrepancy")),
        summary = IntegritySummary(checksRun = 2, discrepancyCount = 1),
    )

    @Test
    fun aCleanLedgerShowsEveryPeriod() {
        val state = IntegrityViewModel(FakeLedgerRepository().apply { integrity = clean }).state.value
        assertFalse(state.loading)
        assertEquals(clean, state.report)
        assertTrue(state.showAll)
        assertEquals(2, state.checks.size)
    }

    @Test
    fun discrepanciesOpenOnTheProblems() {
        val viewModel = IntegrityViewModel(FakeLedgerRepository().apply { integrity = broken })
        assertFalse(viewModel.state.value.showAll)
        assertEquals(listOf("b"), viewModel.state.value.checks.map { it.accountId })
        viewModel.showAll(true)
        assertEquals(2, viewModel.state.value.checks.size)
    }

    @Test
    fun aChosenFilterSurvivesARerun() {
        val ledger = FakeLedgerRepository().apply { integrity = broken }
        val viewModel = IntegrityViewModel(ledger)
        viewModel.showAll(true)
        ledger.integrity = broken.copy(summary = broken.summary.copy(checksRun = 3))
        viewModel.refresh()
        assertTrue(viewModel.state.value.showAll)
        assertEquals(3, viewModel.state.value.report?.summary?.checksRun)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun failureWithNothingToShowIsAnError() {
        val ledger = FakeLedgerRepository().apply { failure = IOException("offline") }
        val viewModel = IntegrityViewModel(ledger)
        assertNotNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.report)

        ledger.failure = null
        ledger.integrity = clean
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertEquals(clean, viewModel.state.value.report)
    }

    @Test
    fun aFailedRerunKeepsTheReportAndSaysSo() {
        val ledger = FakeLedgerRepository().apply { integrity = clean }
        val viewModel = IntegrityViewModel(ledger)
        ledger.failure = IOException("offline")
        viewModel.refresh()
        val state = viewModel.state.value
        assertEquals(clean, state.report)
        assertNull(state.error)
        assertTrue(state.message!!.startsWith("Couldn't re-run the check."))
        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
    }
}
