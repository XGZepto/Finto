package page.zepto.finto.feature.ask

import app.cash.turbine.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.data.repository.AskRepository
import page.zepto.finto.core.model.AskEvent
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.AskTurnContext
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.TotalRow
import page.zepto.finto.core.testing.FakeAskRepository
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import java.io.IOException

class AskViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val totals = listOf(TotalRow("HKD", 3, Money(-4_000, "HKD"), Money(4_000, "HKD"), Money(0, "HKD")))

    private fun answer(question: String) = listOf(
        AskEvent.Step(AskStep(id = "s1", name = "ledger_totals", label = "Totalling dining", state = "running")),
        AskEvent.Data(QueryResult(totals = totals)),
        AskEvent.Step(AskStep(id = "s1", name = "ledger_totals", label = "Totalled dining", state = "done")),
        AskEvent.Text("Let me check. "),
        AskEvent.Discard,
        AskEvent.Text("You spent "),
        AskEvent.Text("**HKD 40.00**."),
        AskEvent.Done(QueryResult(ok = true, question = question, answer = "You spent **HKD 40.00** on $question.", totals = totals)),
    )

    private fun viewModel(repository: AskRepository, thread: AskThread = AskThread()) =
        AskViewModel(repository, thread, FakeLedgerRepository()).apply {
            var now = 1_000L
            clock = { now.also { now += 2_500 } }
        }

    @Test
    fun aStreamedAnswerSettlesIntoADoneTurn() {
        val viewModel = viewModel(FakeAskRepository { answer(it) })
        assertTrue(viewModel.ask("  dining  "))
        val turn = viewModel.state.value.turns.single()
        assertEquals("dining", turn.question)
        assertEquals(TurnState.Done, turn.state)
        assertEquals("You spent **HKD 40.00** on dining.", turn.text)
        assertEquals(listOf("Totalled dining"), turn.steps.map { it.label })
        assertEquals(totals, turn.data.totals)
        assertEquals(2.5, turn.seconds!!, 1e-9)
        assertNull(turn.error)
        assertFalse(viewModel.state.value.asking)
    }

    @Test
    fun eventsApplyAsTheyArrive() {
        val repository = Scripted()
        val viewModel = viewModel(repository)
        viewModel.ask("dining")
        fun turn() = viewModel.state.value.turns.single()
        assertEquals(TurnState.Asking, turn().state)

        repository.events.trySend(AskEvent.Step(AskStep(id = "s1", label = "Totalling dining", state = "running")))
        repository.events.trySend(AskEvent.Data(QueryResult(totals = totals)))
        assertEquals(listOf("running"), turn().steps.map { it.state })
        assertEquals(totals, turn().data.totals)

        repository.events.trySend(AskEvent.Text("Let me check. "))
        assertEquals("Let me check. ", turn().text)
        repository.events.trySend(AskEvent.Discard)
        assertEquals("", turn().text)
        repository.events.trySend(AskEvent.Text("You spent "))
        repository.events.trySend(AskEvent.Text("**HKD 40.00**."))
        assertEquals("You spent **HKD 40.00**.", turn().text)
        assertTrue(viewModel.state.value.asking)

        repository.events.close()
        assertEquals(AskViewModel.CUT_OFF, turn().error)
        assertEquals("done", turn().steps.single().state)
    }

    @Test
    fun aStreamThatEndsWithoutDoneIsCutOff() {
        val viewModel = viewModel(FakeAskRepository { answer(it).dropLast(1) })
        viewModel.ask("dining")
        val turn = viewModel.state.value.turns.single()
        assertEquals(TurnState.Error, turn.state)
        assertEquals(AskViewModel.CUT_OFF, turn.error)
        assertEquals("You spent **HKD 40.00**.", turn.text)
        assertTrue("running steps settle", turn.steps.none { it.state == "running" })
    }

    @Test
    fun aRefusalFromTheServerIsShownAsItsError() {
        val viewModel = viewModel(FakeAskRepository { listOf(AskEvent.Done(QueryResult(ok = false, error = "Ask is not configured."))) })
        viewModel.ask("dining")
        val turn = viewModel.state.value.turns.single()
        assertEquals(TurnState.Error, turn.state)
        assertEquals("Ask is not configured.", turn.error)
    }

    @Test
    fun aFailureBeforeAnythingArrivesSaysWhy() {
        val viewModel = viewModel(Throwing(afterFirst = false))
        viewModel.ask("dining")
        assertEquals("boom", viewModel.state.value.turns.single().error)
    }

    @Test
    fun aFailureMidStreamIsACutOff() {
        val viewModel = viewModel(Throwing(afterFirst = true))
        viewModel.ask("dining")
        assertEquals(AskViewModel.CUT_OFF, viewModel.state.value.turns.single().error)
    }

    @Test
    fun stopEndsTheTurnAsStopped() = runTest {
        val viewModel = viewModel(Endless())
        viewModel.effects.test {
            viewModel.ask("dining")
            assertTrue(viewModel.state.value.asking)
            assertFalse("only one question at a time", viewModel.ask("again"))
            viewModel.stop()
            assertEquals(AskEffect.Finished(ok = false), awaitItem())
        }
        val turn = viewModel.state.value.turns.single()
        assertEquals(TurnState.Error, turn.state)
        assertEquals(AskViewModel.STOPPED, turn.error)
        assertFalse(viewModel.state.value.asking)
    }

    @Test
    fun followUpsCarryTheLastThreeAnsweredTurns() {
        val repository = FakeAskRepository { answer(it) }
        val viewModel = viewModel(repository)
        listOf("one", "two", "three", "four").forEach { viewModel.ask(it) }
        viewModel.ask("five")
        val (question, history) = repository.asked.last()
        assertEquals("five", question)
        assertEquals(listOf("two", "three", "four"), history.map(AskTurnContext::question))
        assertEquals("You spent **HKD 40.00** on four.", history.last().answer)
    }

    @Test
    fun longAnswersAreTrimmedInHistoryAndEmptyQuestionsAreIgnored() {
        val long = "x".repeat(2_000)
        val repository = FakeAskRepository { listOf(AskEvent.Done(QueryResult(answer = long))) }
        val viewModel = viewModel(repository)
        assertFalse(viewModel.ask("   "))
        viewModel.ask("one")
        viewModel.ask("two")
        assertEquals(800, repository.asked.last().second.single().answer.length)
    }

    @Test
    fun theThreadKeepsTwelveTurnsAndSurvivesLeaving() {
        val thread = AskThread()
        val first = viewModel(FakeAskRepository { answer(it) }, thread)
        (1..14).forEach { first.ask("q$it") }
        assertEquals(12, first.state.value.turns.size)
        assertEquals("q3", first.state.value.turns.first().question)

        val again = viewModel(FakeAskRepository { answer(it) }, thread)
        assertEquals(first.state.value.turns, again.state.value.turns)
        again.newConversation()
        assertTrue(again.state.value.turns.isEmpty())
        assertTrue(thread.turns.isEmpty())
    }

    @Test
    fun aTurnStillAskingWhenAskWasLeftIsNotRestored() {
        val thread = AskThread().apply {
            turns = listOf(AskTurn(id = 1, question = "done", state = TurnState.Done), AskTurn(id = 2, question = "lost"))
        }
        val viewModel = viewModel(FakeAskRepository(), thread)
        assertEquals(listOf("done"), viewModel.state.value.turns.map { it.question })
    }

    @Test
    fun rowsExpandPerTurn() {
        val viewModel = viewModel(FakeAskRepository { answer(it) })
        viewModel.ask("dining")
        val id = viewModel.state.value.turns.single().id
        viewModel.toggleRows(id)
        assertEquals(setOf(id), viewModel.state.value.expanded)
        viewModel.toggleRows(id)
        assertTrue(viewModel.state.value.expanded.isEmpty())
    }

    @Test
    fun finishingSignalsSuccess() = runTest {
        val viewModel = viewModel(FakeAskRepository { answer(it) })
        viewModel.effects.test {
            viewModel.ask("dining")
            assertEquals(AskEffect.Finished(ok = true), awaitItem())
        }
    }

    /** Fails before or after one step. */
    private class Throwing(private val afterFirst: Boolean) : AskRepository {
        override fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent> = flow {
            if (afterFirst) emit(AskEvent.Step(AskStep(id = "s1", label = "Reading")))
            throw if (afterFirst) IOException("reset") else IllegalStateException("boom")
        }
    }

    /** Delivers whatever the test sends, until the test closes it. */
    private class Scripted : AskRepository {
        val events = Channel<AskEvent>(Channel.UNLIMITED)
        override fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent> = events.receiveAsFlow()
    }

    /** A step, then nothing until cancelled. */
    private class Endless : AskRepository {
        override fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent> = flow {
            emit(AskEvent.Step(AskStep(id = "s1", label = "Reading", state = "running")))
            awaitCancellation()
        }
    }
}
