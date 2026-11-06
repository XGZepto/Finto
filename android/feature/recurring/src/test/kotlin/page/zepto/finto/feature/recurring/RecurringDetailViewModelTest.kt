package page.zepto.finto.feature.recurring

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class RecurringDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val netflix = Sample.monthly("Netflix", -7800, 5)
    private val fake = FakeLedgerRepository().apply {
        pageFor = { filter -> if (filter.tags == listOf("subscription")) netflix else Sample.monthly("Gym", -40_000, 2) }
    }

    private fun viewModel(key: String) = RecurringDetailViewModel(SavedStateHandle(mapOf("key" to key)), fake, FakeSessionRepository())

    @Test
    fun findsTheCommitmentByItsKey() {
        val state = viewModel("netflix").state.value
        assertFalse(state.loading)
        val commitment = assertNotNull(state.commitment).let { state.commitment!! }
        assertEquals("Netflix", commitment.merchant)
        assertEquals(5, commitment.count)
        assertEquals(Cadence.Monthly, commitment.cadence)
    }

    @Test
    fun anUnknownKeyLoadsToNothing() {
        val state = viewModel("hulu").state.value
        assertFalse(state.loading)
        assertNull(state.commitment)
        assertNull(state.error)
    }

    @Test
    fun aFailureWithNothingCachedIsAnError() {
        fake.failure = IOException("offline")
        val state = viewModel("netflix").state.value
        assertNotNull(state.error)
        assertNull(state.commitment)
    }

    @Test
    fun aFailedRefreshKeepsTheCommitment() {
        val viewModel = viewModel("netflix")
        fake.failure = IOException("offline")
        viewModel.refresh()
        assertNotNull(viewModel.state.value.commitment)
        assertNotNull(viewModel.state.value.notice)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun linksOpenTheBlotterOnTheMerchantOrTheCharge() {
        val commitment = viewModel("netflix").state.value.commitment!!
        assertEquals(LedgerFilter(query = "Netflix", tags = listOf("subscription")), blotterFilter(commitment))
        val txn = commitment.charges.first()
        assertEquals(LedgerFilter(accounts = listOf(txn.accountId), from = txn.date, to = txn.date), chargeFilter(txn))
    }
}
