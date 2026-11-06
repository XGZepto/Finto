package page.zepto.finto.feature.recurring

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import page.zepto.finto.core.testing.TestData
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/** Charges a few months back from today, so cadence and lapse read the same whenever the test runs. */
internal object Sample {
    val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    fun monthly(merchant: String, amount: Long, count: Int, lastDaysAgo: Long = 10, prefix: String = merchant.lowercase()): List<Txn> =
        (0 until count).map { index ->
            TestData.txn(
                id = "$prefix-$index",
                date = today.minusDays(lastDaysAgo + 30L * index).toString(),
                description = merchant.uppercase(),
                amount = amount,
                merchant = merchant,
                category = "subscriptions",
            )
        }

    fun plan(id: String, principal: Long, term: Int, merchant: String = "Apple Store") = InstallmentPlan(
        id = id,
        accountId = "hsbc_visa",
        merchant = merchant,
        description = "$merchant instalment",
        principal = Money(principal, "HKD"),
        termMonths = term,
        startDate = today.minusMonths(3).toString(),
        paidCount = 3,
        remainingCount = term - 3,
        outstanding = Money(principal / term * (term - 3), "HKD"),
        perInstallment = Money(principal / term, "HKD"),
    )
}

/** Records the convert-to currency and limit of each page read. */
internal class RecordingLedger(private val fake: FakeLedgerRepository) : LedgerRepository by fake {
    val reads = mutableListOf<Triple<LedgerFilter, Int, String?>>()
    val installmentReads = mutableListOf<Pair<Boolean, ReadPolicy>>()

    override suspend fun transactionsPage(filter: LedgerFilter, limit: Int, offset: Int, convertTo: String?, policy: ReadPolicy): TxnPage {
        reads += Triple(filter, limit, convertTo)
        return fake.transactionsPage(filter, limit, offset, convertTo, policy)
    }

    override suspend fun installments(activeOnly: Boolean, policy: ReadPolicy): InstallmentsResponse {
        installmentReads += activeOnly to policy
        return fake.installments(activeOnly, policy)
    }
}

class RecurringViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val netflix = Sample.monthly("Netflix", -7800, 4)
    private val spotify = Sample.monthly("Spotify", -5900, 3)
    private val gym = Sample.monthly("Gym", -40_000, 3, lastDaysAgo = 120)

    private val fake = FakeLedgerRepository().apply {
        pageFor = { filter ->
            when (filter.tags) {
                listOf("subscription") -> netflix + spotify.take(2)
                // Spotify's newest charge carries both tags: it must be counted once.
                listOf("recurring") -> spotify + gym
                else -> emptyList()
            }
        }
        installments = InstallmentsResponse(plans = listOf(Sample.plan("iphone", -1_200_000, 12)))
    }
    private val ledger = RecordingLedger(fake)

    @Test
    fun readsBothTagsAndThePlansThenBuildsTheView() {
        val state = RecurringViewModel(ledger, FakeSessionRepository()).state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(setOf(listOf("subscription"), listOf("recurring")), ledger.reads.map { it.first.tags }.toSet())
        assertTrue(ledger.reads.all { it.second == 500 && it.third == "HKD" })
        assertTrue(ledger.installmentReads.all { it.first })

        assertEquals(listOf("Netflix", "Spotify"), state.view.activeSubscriptions.map { it.merchant })
        assertEquals(3, state.commitments.first { it.key == "spotify" }.count)
        assertEquals(listOf("Gym"), state.view.inactiveSubscriptions.map { it.merchant })
        assertEquals(1, state.view.plans.size)
        // 7800 + 5900 + 100000 (the plan); the gym has lapsed.
        assertEquals(Money(-113_700, "HKD"), state.view.monthlyTotal)
    }

    @Test
    fun filtersRecomputeTheViewWithoutReading() {
        val viewModel = RecurringViewModel(ledger, FakeSessionRepository())
        val reads = ledger.reads.size
        viewModel.setSearch("spot")
        assertEquals(listOf("Spotify"), viewModel.state.value.view.activeSubscriptions.map { it.merchant })
        assertTrue(viewModel.state.value.view.plans.isEmpty())
        viewModel.setSearch("")
        viewModel.setKind(RecurringKind.Instalments)
        assertTrue(viewModel.state.value.view.activeSubscriptions.isEmpty())
        assertEquals(Money(-100_000, "HKD"), viewModel.state.value.view.monthlyTotal)
        viewModel.setKind(RecurringKind.All)
        viewModel.setCadence(Cadence.Yearly)
        assertTrue(viewModel.state.value.view.isEmpty)
        assertTrue(viewModel.state.value.filter.isActive)
        assertEquals(reads, ledger.reads.size)
    }

    @Test
    fun inactiveListToggles() = runTest {
        val viewModel = RecurringViewModel(ledger, FakeSessionRepository())
        viewModel.state.test {
            assertFalse(awaitItem().inactiveExpanded)
            viewModel.toggleInactive()
            assertTrue(awaitItem().inactiveExpanded)
            viewModel.toggleInactive()
            assertFalse(awaitItem().inactiveExpanded)
        }
    }

    @Test
    fun aFailureWithNothingToShowIsAnError() {
        fake.failure = IOException("offline")
        val viewModel = RecurringViewModel(ledger, FakeSessionRepository())
        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNotNull(state.error)
        assertFalse(state.hasData)

        fake.failure = null
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(2, viewModel.state.value.view.activeSubscriptions.size)
    }

    @Test
    fun aFailedRefreshKeepsWhatIsShownAndSaysSo() {
        val viewModel = RecurringViewModel(ledger, FakeSessionRepository())
        fake.failure = IOException("offline")
        viewModel.refresh()
        val state = viewModel.state.value
        assertNull(state.error)
        assertNotNull(state.notice)
        assertTrue(state.hasData)
        viewModel.noticeShown()
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun refreshAsksTheNetwork() {
        val viewModel = RecurringViewModel(ledger, FakeSessionRepository())
        ledger.installmentReads.clear()
        viewModel.refresh()
        assertEquals(listOf(true to ReadPolicy.Network), ledger.installmentReads)
    }

    @Test
    fun aNewReportingCurrencyReloads() = runTest {
        val session = FakeSessionRepository()
        val viewModel = RecurringViewModel(ledger, session)
        session.setBaseCurrency("USD")
        assertEquals("USD", viewModel.state.value.currency)
        assertEquals("USD", ledger.reads.last().third)
        assertEquals("USD", viewModel.state.value.view.monthlyTotal.currency)
    }

    @Test
    fun nothingTaggedIsEmptyNotAnError() {
        fake.pageFor = { emptyList() }
        fake.installments = InstallmentsResponse()
        val state = RecurringViewModel(ledger, FakeSessionRepository()).state.value
        assertFalse(state.hasData)
        assertNull(state.error)
        assertTrue(state.view.isEmpty)
    }
}
