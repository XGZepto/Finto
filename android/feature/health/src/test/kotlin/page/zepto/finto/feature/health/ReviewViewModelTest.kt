package page.zepto.finto.feature.health

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.ReviewRepository
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue
import page.zepto.finto.core.model.ReviewQueueResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeReviewRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class ReviewViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun candidate(id: String, score: Double = 0.9) = ReviewCandidate(id = id, score = score, outDesc = id)

    private fun queue(vararg ids: String) = ReviewQueueResponse(ids.map { candidate(it) }, ids.size)

    private val ledger = FakeLedgerRepository().apply {
        accounts = listOf(Account(id = "amex", institutionId = "amex_hk", displayName = "AMEX Platinum", accountType = "credit_card", primaryCurrency = "HKD"))
    }

    @Test
    fun opensOnTheFirstQueueWithWork() {
        val reviews = FakeReviewRepository(mapOf(ReviewQueue.Transfers to queue("t1", "t2"), ReviewQueue.Installments to queue("i1")))
        val state = ReviewViewModel(reviews, ledger).state.value
        assertEquals(ReviewQueue.Transfers, state.queue)
        assertEquals(listOf("t1", "t2"), state.items.map { it.id })
        assertEquals(0, state.count(ReviewQueue.Duplicates))
        assertEquals(2, state.count(ReviewQueue.Transfers))
        assertEquals(1, state.count(ReviewQueue.Installments))
        assertEquals("AMEX Platinum", state.accountName("amex"))
        assertEquals("unknown", state.accountName("unknown"))
    }

    @Test
    fun staysOnTheFirstQueueWhenItHasWorkOrNothingDoes() {
        val busy = FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1"), ReviewQueue.Transfers to queue("t1")))
        assertEquals(ReviewQueue.Duplicates, ReviewViewModel(busy, ledger).state.value.queue)
        val clear = ReviewViewModel(FakeReviewRepository(), ledger).state.value
        assertEquals(ReviewQueue.Duplicates, clear.queue)
        assertFalse(clear.loading)
        assertTrue(clear.items.isEmpty())
    }

    @Test
    fun pickingAQueueShowsItFromTheTop() {
        val reviews = FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1", "d2"), ReviewQueue.Installments to queue("i1")))
        val viewModel = ReviewViewModel(reviews, ledger)
        viewModel.move(1)
        assertEquals(1, viewModel.state.value.cursor)
        viewModel.select(ReviewQueue.Installments)
        assertEquals(ReviewQueue.Installments, viewModel.state.value.queue)
        assertEquals(0, viewModel.state.value.cursor)
        assertEquals("i1", viewModel.state.value.candidate?.id)
    }

    @Test
    fun moveStaysWithinTheQueue() {
        val viewModel = ReviewViewModel(FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1", "d2"))), ledger)
        viewModel.move(-1)
        assertEquals(0, viewModel.state.value.cursor)
        viewModel.move(1)
        viewModel.move(1)
        assertEquals(1, viewModel.state.value.cursor)
    }

    @Test
    fun acceptingRemovesTheCandidateAtOnceAndSendsTheDecision() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reviews = GatedReviews(FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1", "d2", "d3"))), gate)
        val viewModel = ReviewViewModel(reviews, ledger)
        viewModel.move(2)
        viewModel.resolve(accept = true)

        // Before the server answers: gone from the list, the count down, the cursor on the new last item.
        val pending = viewModel.state.value
        assertEquals(listOf("d1", "d2"), pending.items.map { it.id })
        assertEquals(2, pending.count(ReviewQueue.Duplicates))
        assertEquals(1, pending.cursor)

        gate.complete(Unit)
        assertEquals(listOf(Triple(ReviewQueue.Duplicates, "d3", true)), reviews.delegate.resolved)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun aFailedDecisionPutsTheCandidateBackAndSaysWhy() = runTest {
        val fake = FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1", "d2")))
        val viewModel = ReviewViewModel(fake, ledger)
        fake.failure = IOException("offline")
        viewModel.state.test {
            assertEquals(listOf("d1", "d2"), awaitItem().items.map { it.id })
            viewModel.resolve(accept = false)
            assertEquals(listOf("d2"), awaitItem().items.map { it.id })
            val restored = awaitItem()
            assertEquals(listOf("d1", "d2"), restored.items.map { it.id })
            assertEquals(2, restored.count(ReviewQueue.Duplicates))
            assertEquals(0, restored.cursor)
            assertTrue(restored.message!!.startsWith("Couldn't reject it."))
            viewModel.messageShown()
            assertNull(awaitItem().message)
        }
    }

    @Test
    fun aDecisionInFlightIsNotBroughtBackByARefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reviews = GatedReviews(FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1", "d2"))), gate)
        val viewModel = ReviewViewModel(reviews, ledger)
        viewModel.resolve(accept = true)
        viewModel.refresh()
        assertEquals(listOf("d2"), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.count(ReviewQueue.Duplicates))
        gate.complete(Unit)
    }

    @Test
    fun aQueueThatCannotBeReadShowsAnErrorAndRetries() {
        val fake = FakeReviewRepository().apply { failure = IOException("offline") }
        val viewModel = ReviewViewModel(fake, ledger)
        val failed = viewModel.state.value
        assertNotNull(failed.current.error)
        assertFalse(failed.loading)

        fake.failure = null
        fake.queues = mapOf(ReviewQueue.Duplicates to queue("d1"))
        viewModel.retry()
        assertNull(viewModel.state.value.current.error)
        assertEquals("d1", viewModel.state.value.candidate?.id)
    }

    @Test
    fun aFailedRefreshKeepsWhatIsShown() {
        val fake = FakeReviewRepository(mapOf(ReviewQueue.Duplicates to queue("d1")))
        val viewModel = ReviewViewModel(fake, ledger)
        fake.failure = IOException("offline")
        viewModel.refresh()
        val state = viewModel.state.value
        assertEquals("d1", state.candidate?.id)
        assertFalse(state.refreshing)
        assertTrue(state.message!!.startsWith("Couldn't refresh."))
    }

    /** Holds every decision until [gate] opens, so the optimistic state can be seen. */
    private class GatedReviews(val delegate: FakeReviewRepository, private val gate: CompletableDeferred<Unit>) : ReviewRepository {
        override suspend fun queue(queue: ReviewQueue, policy: ReadPolicy) = delegate.queue(queue, policy)
        override suspend fun resolve(queue: ReviewQueue, id: String, accept: Boolean) {
            gate.await()
            delegate.resolve(queue, id, accept)
        }
    }
}
