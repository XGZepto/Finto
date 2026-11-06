package page.zepto.finto.feature.recurring

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.CurrencyAmount
import page.zepto.finto.core.model.InstallmentCharge
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class InstallmentsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val active = Sample.plan("iphone", -1_200_000, 12)
    private val done = Sample.plan("sofa", -600_000, 6, merchant = "IKEA").copy(status = "completed", paidCount = 6)
    private val fake = FakeLedgerRepository().apply {
        installments = InstallmentsResponse(
            plans = listOf(active, done),
            outstandingByCurrency = listOf(CurrencyAmount("HKD", -900_000)),
            committedMonthlyByCurrency = listOf(CurrencyAmount("HKD", -100_000)),
        )
    }
    private val ledger = RecordingLedger(fake)

    @Test
    fun loadsActivePlansByDefault() {
        val state = InstallmentsViewModel(ledger).state.value
        assertTrue(state.activeOnly)
        assertFalse(state.loading)
        assertTrue(ledger.installmentReads.all { it.first })
        assertEquals(2, state.plans.size)
        assertEquals("only plans still running count as active", 1, state.activeCount)
        assertEquals(listOf(CurrencyAmount("HKD", -900_000)), state.response?.outstandingByCurrency)
    }

    @Test
    fun theToggleReadsAllPlans() = runTest {
        val viewModel = InstallmentsViewModel(ledger)
        viewModel.state.test {
            assertTrue(awaitItem().activeOnly)
            viewModel.setActiveOnly(false)
            // The other list is a different read: its plans replace the old ones rather than mixing with them.
            var state = awaitItem()
            while (state.loading || state.response == null) state = awaitItem()
            assertFalse(state.activeOnly)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(ledger.installmentReads.last().first)
    }

    @Test
    fun aFailureWithNothingShownIsAnError() {
        fake.failure = IOException("offline")
        val viewModel = InstallmentsViewModel(ledger)
        assertNotNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.response)
        fake.failure = null
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertEquals(2, viewModel.state.value.plans.size)
    }

    @Test
    fun aFailedRefreshKeepsThePlans() {
        val viewModel = InstallmentsViewModel(ledger)
        fake.failure = IOException("offline")
        viewModel.refresh()
        assertNotNull(viewModel.state.value.notice)
        assertEquals(2, viewModel.state.value.plans.size)
        assertFalse(viewModel.state.value.refreshing)
    }
}

class InstallmentDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val plan = Sample.plan("iphone", -1_200_000, 12).copy(
        charges = listOf(
            InstallmentCharge("c2", Sample.today.minusMonths(1).toString(), "APPLE STORE 2/12", -100_000, "HKD", installmentSeq = 2),
            InstallmentCharge("c3", Sample.today.toString(), "APPLE STORE 3/12", -100_000, "HKD", installmentSeq = 3),
        ),
    )
    private val fake = FakeLedgerRepository().apply { installmentDetails = mapOf("iphone" to plan) }

    private fun viewModel() = InstallmentDetailViewModel(SavedStateHandle(mapOf("id" to "iphone")), fake)

    @Test
    fun loadsThePlanAndItsSchedule() {
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertEquals("iphone", state.plan?.id)
        assertEquals(12, state.schedule.size)
        assertEquals(ScheduleState.Billed, state.schedule[0].state)
        assertEquals(ScheduleState.Charged, state.schedule[1].state)
        assertEquals(ScheduleState.Projected, state.schedule[3].state)
    }

    @Test
    fun aFailureIsAnErrorUntilARefreshSucceeds() {
        fake.failure = IOException("offline")
        val viewModel = viewModel()
        assertNotNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.plan)
        fake.failure = null
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertNotNull(viewModel.state.value.plan)
    }

    @Test
    fun aFailedRefreshKeepsTheSchedule() {
        val viewModel = viewModel()
        fake.failure = IOException("offline")
        viewModel.refresh()
        assertNotNull(viewModel.state.value.notice)
        assertEquals(12, viewModel.state.value.schedule.size)
    }
}
