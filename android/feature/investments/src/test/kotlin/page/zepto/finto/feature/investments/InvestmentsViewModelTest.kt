package page.zepto.finto.feature.investments

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
import page.zepto.finto.core.model.Holding
import page.zepto.finto.core.model.InvestmentActivity
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentHistory
import page.zepto.finto.core.model.InvestmentSnapshot
import page.zepto.finto.core.model.InvestmentSubaccount
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ValuePoint
import page.zepto.finto.core.testing.FakeInvestmentsRepository
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

internal object InvestmentSamples {
    fun hkd(amount: Long) = Money(amount, "HKD")

    val snapshots = listOf(
        InvestmentSnapshot("s-sep", "2026-09-30", "hsbc_mpf", hkd(52_345_678)),
        InvestmentSnapshot("s-jun", "2026-06-30", "hsbc_mpf", hkd(50_100_000)),
        InvestmentSnapshot("s-old", "2025-12-31", "boc_mpf", hkd(9_000_000)),
    )

    private fun detail(id: String, date: String, scheme: String, total: Long, employee: Long) = InvestmentDetail(
        id = id,
        asOfDate = date,
        scheme = scheme,
        total = hkd(total),
        subaccounts = listOf(
            InvestmentSubaccount("mpf_employee", "M-1", hkd(employee), "0.6000"),
            InvestmentSubaccount("mpf_tvc", "M-2", hkd(total - employee), "0.4000"),
        ),
        holdings = listOf(
            Holding("Global Equity Fund", units = "1234.5678", marketValue = hkd(total * 6 / 10), allocation = "0.6"),
            Holding("MPF Conservative Fund", units = "800.1", marketValue = hkd(total * 4 / 10), allocation = "0.4"),
        ),
    )

    val details = mapOf(
        "s-sep" to detail("s-sep", "2026-09-30", "hsbc_mpf", 52_345_678, 31_000_000),
        "s-jun" to detail("s-jun", "2026-06-30", "hsbc_mpf", 50_100_000, 30_000_000),
        "s-old" to detail("s-old", "2025-12-31", "boc_mpf", 9_000_000, 9_000_000),
    )

    fun history(scheme: String?, accountId: String?): InvestmentHistory = InvestmentHistory(
        scheme,
        accountId,
        if (accountId == "mpf_employee") {
            listOf(ValuePoint("2026-06-30", hkd(30_000_000)), ValuePoint("2026-09-30", hkd(31_000_000)))
        } else {
            listOf(ValuePoint("2026-03-31", hkd(48_000_000)), ValuePoint("2026-06-30", hkd(50_100_000)), ValuePoint("2026-09-30", hkd(52_345_678)))
        },
    )

    fun activities(accountId: String?): List<InvestmentActivity> = listOf(
        InvestmentActivity("a1", "mpf_employee", "M-1", "2026-09-15", "employee_mandatory", "regular_contribution", hkd(150_000)),
        InvestmentActivity("a2", "mpf_tvc", "M-2", "2026-09-10", "tvc", "regular_contribution", hkd(500_000)),
    ).filter { accountId == null || it.accountId == accountId }
}

class InvestmentsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val historyCalls = mutableListOf<Pair<String?, String?>>()
    private val activityCalls = mutableListOf<String?>()
    private val repository = FakeInvestmentsRepository().apply {
        snapshots = InvestmentSamples.snapshots
        details = InvestmentSamples.details
        history = { scheme, account -> historyCalls += scheme to account; InvestmentSamples.history(scheme, account) }
        activities = { account -> activityCalls += account; InvestmentSamples.activities(account) }
    }
    private val ledger = FakeLedgerRepository().apply {
        accounts = listOf(Account("mpf_employee", "hsbc", "Employee account", "mpf", "HKD"))
    }

    private fun viewModel() = InvestmentsViewModel(repository, ledger)

    @Test
    fun opensTheNewestValuationOfTheFirstScheme() {
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertEquals("hsbc_mpf", state.scheme)
        assertEquals("s-sep", state.current?.id)
        assertEquals(listOf("hsbc_mpf", "boc_mpf"), state.schemes)
        assertEquals(listOf("s-sep", "s-jun"), state.scopedSnapshots.map { it.id })
        assertEquals(InvestmentSamples.hkd(52_345_678), state.displayedValue)
        assertEquals("HSBC MPF", state.title)
        assertTrue(historyCalls.all { it == ("hsbc_mpf" to null) })
        assertTrue(activityCalls.all { it == null })
        assertEquals(2, state.activities.size)
        val change = state.change!!
        assertEquals(InvestmentSamples.hkd(2_245_678), change.amount)
    }

    @Test
    fun aMemberAccountScopesValueHistoryAndContributions() {
        val viewModel = viewModel()
        viewModel.selectAccount("mpf_employee")
        val state = viewModel.state.value
        assertEquals("mpf_employee", state.accountId)
        assertEquals(InvestmentSamples.hkd(31_000_000), state.displayedValue)
        assertEquals("Employee account", state.title)
        assertEquals("hsbc_mpf" to "mpf_employee", historyCalls.last())
        assertEquals("mpf_employee", activityCalls.last())
        assertEquals(listOf("a1"), state.activities.map { it.id })
        assertEquals(InvestmentSamples.hkd(1_000_000), state.change?.amount)

        viewModel.clearAccount()
        assertNull(viewModel.state.value.accountId)
        assertEquals(InvestmentSamples.hkd(52_345_678), viewModel.state.value.displayedValue)
        assertEquals(2, viewModel.state.value.activities.size)
        assertEquals("hsbc_mpf" to null, historyCalls.last())
    }

    @Test
    fun anAccountWithoutAStoredNameIsHumanised() {
        val viewModel = viewModel()
        viewModel.selectAccount("mpf_tvc")
        assertEquals("MPF tvc", viewModel.state.value.title)
    }

    @Test
    fun anOlderValuationComparesWithTheOneBeforeIt() = runTest {
        val viewModel = viewModel()
        viewModel.state.test {
            assertEquals("s-sep", awaitItem().current?.id)
            viewModel.selectSnapshot("s-jun")
            var state = awaitItem()
            while (state.current?.id != "s-jun") state = awaitItem()
            assertEquals(2, state.visibleHistory.size)
            assertEquals(InvestmentSamples.hkd(2_100_000), state.change?.amount)
            assertFalse(state.opening)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anotherSchemeOpensItsNewestValuation() {
        val viewModel = viewModel()
        viewModel.selectAccount("mpf_employee")
        viewModel.selectScheme("boc_mpf")
        val state = viewModel.state.value
        assertEquals("boc_mpf", state.scheme)
        assertNull(state.accountId)
        assertEquals("s-old", state.current?.id)
        assertEquals("boc_mpf" to null, historyCalls.last())
    }

    @Test
    fun noSnapshotsIsEmptyNotAnError() {
        repository.snapshots = emptyList()
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertTrue(state.snapshots.isEmpty())
        assertNull(state.error)
        assertNull(state.current)
    }

    @Test
    fun aFailureWithNothingShownIsAnErrorUntilRefreshed() {
        repository.failure = IOException("offline")
        val viewModel = viewModel()
        assertNotNull(viewModel.state.value.error)
        repository.failure = null
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertEquals("s-sep", viewModel.state.value.current?.id)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun aFailedRefreshKeepsTheValuation() {
        val viewModel = viewModel()
        repository.failure = IOException("offline")
        viewModel.refresh()
        val state = viewModel.state.value
        assertNull(state.error)
        assertNotNull(state.notice)
        assertEquals("s-sep", state.current?.id)
        viewModel.noticeShown()
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun aSnapshotThatCannotBeOpenedSaysSo() {
        repository.details = emptyMap()
        val state = viewModel().state.value
        assertNull(state.current)
        assertNotNull(state.openError)
        assertFalse(state.opening)
    }
}
