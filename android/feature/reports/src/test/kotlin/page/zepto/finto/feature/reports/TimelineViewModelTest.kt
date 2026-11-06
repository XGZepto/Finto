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
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.CompositionSeries
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.CoverageAccount
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class TimelineViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val requested = mutableListOf<Pair<String, String>>()

    private val ledger = FakeLedgerRepository().apply {
        compositions = { dimension ->
            Composition(
                dimension = dimension,
                currency = "HKD",
                months = listOf("2026-09", "2026-10"),
                series = listOf(CompositionSeries("dining", total = 300, values = listOf(100, 200))),
            )
        }
        coverage = Coverage(
            months = listOf("2026-09", "2026-10"),
            accounts = listOf(CoverageAccount("mox", "Mox", cells = listOf("pre", "export"), exportMonths = 1)),
        )
    }

    /** Records the currency each composition is converted into. */
    private val recording: LedgerRepository = object : LedgerRepository by ledger {
        override suspend fun composition(dimension: String, convertTo: String, policy: ReadPolicy): Composition {
            requested += dimension to convertTo
            return ledger.composition(dimension, convertTo, policy)
        }
    }

    @Test
    fun loadsCompositionAndCoverage() {
        val state = TimelineViewModel(recording, FakeSessionRepository()).state.value
        assertFalse(state.loading)
        assertEquals("category", state.composition?.dimension)
        assertEquals(listOf("category" to "HKD"), requested.distinct())
        assertEquals(listOf("2026-09", "2026-10"), state.columns.map { it.month })
        assertEquals(mapOf("mox" to "Mox"), state.accountNames)
        assertEquals(1, state.coverage?.accounts?.size)
    }

    @Test
    fun changingTheDimensionReloads() = runTest {
        val viewModel = TimelineViewModel(recording, FakeSessionRepository())
        viewModel.state.test {
            assertEquals("category", awaitItem().composition?.dimension)
            viewModel.setDimension("merchant")
            var item = awaitItem()
            while (item.composition?.dimension != "merchant") item = awaitItem()
            assertEquals("merchant", item.dimension)
            assertFalse(item.loading)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("merchant" to "HKD", requested.last())
    }

    @Test
    fun scaleChangesTheColumnsWithoutReading() {
        val viewModel = TimelineViewModel(recording, FakeSessionRepository())
        val reads = requested.size
        assertEquals(1f, viewModel.state.value.columns[0].segments[0].height, 1e-6f)
        viewModel.setScale(TimelineScale.Amount)
        assertEquals(TimelineScale.Amount, viewModel.state.value.scale)
        assertEquals(0.5f, viewModel.state.value.columns[0].segments[0].height, 1e-6f)
        assertEquals(reads, requested.size)
    }

    @Test
    fun followsTheReportingCurrency() = runTest {
        val session = FakeSessionRepository()
        TimelineViewModel(recording, session)
        session.setBaseCurrency("USD")
        assertEquals("category" to "USD", requested.last())
    }

    @Test
    fun aFailureWithNothingShownIsAnError() {
        ledger.failure = IOException("offline")
        val state = TimelineViewModel(recording, FakeSessionRepository()).state.value
        assertNull(state.composition)
        assertNull(state.coverage)
        assertFalse(state.loading)
        assertNotNull(state.error)
    }

    @Test
    fun refreshRecoversAndCoverageThatFailsIsLeftOut() {
        val noCoverage = object : LedgerRepository by recording {
            override suspend fun coverage(policy: ReadPolicy): Coverage = throw IOException("offline")
        }
        ledger.failure = IOException("offline")
        val viewModel = TimelineViewModel(noCoverage, FakeSessionRepository())
        assertNotNull(viewModel.state.value.error)
        ledger.failure = null
        viewModel.refresh()
        val state = viewModel.state.value
        assertNull(state.error)
        assertNotNull(state.composition)
        assertNull(state.coverage)
        assertFalse(state.refreshing)
    }

    @Test
    fun errorShownClearsTheError() {
        ledger.failure = IOException("offline")
        val viewModel = TimelineViewModel(recording, FakeSessionRepository())
        viewModel.errorShown()
        assertTrue(viewModel.state.value.error == null)
    }
}
