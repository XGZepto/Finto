package page.zepto.finto.feature.transactions

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.CategorySuggestion
import page.zepto.finto.core.model.Facets
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Patch
import page.zepto.finto.core.model.SuggestionResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import page.zepto.finto.core.testing.TestData

class ActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val coffee = TestData.txn("t1", "2026-11-01", "COFFEE 123", -45_00, category = null)
    private val ledger = FakeLedgerRepository().apply {
        transactions = listOf(coffee)
        facets = Facets(categories = listOf("groceries", "dining"))
        suggestion = SuggestionResponse(available = true, suggestion = CategorySuggestion("dining", "coffee", "Blue Bottle", confidence = 0.91))
    }

    private fun viewModel(vararg args: Pair<String, Any?>) = ActivityViewModel(SavedStateHandle(mapOf(*args)), ledger)

    @Test
    fun aScopedBlotterKeepsItsFilterAndTitle() {
        val filter = LedgerFilter(accounts = listOf("amex"), from = "2026-10-01", spendingOnly = true)
        val vm = viewModel("filter" to FintoJson.encodeToString(LedgerFilter.serializer(), filter), "title" to "AMEX")
        val collector = CoroutineScope(Dispatchers.Main).launch { vm.state.collect {} }
        val state = vm.state.value
        collector.cancel()
        assertEquals("AMEX", state.title)
        assertTrue(state.scoped)
        assertTrue(state.accountLocked)
    }

    @Test
    fun arrivingWithATransactionOpensIt() {
        val vm = viewModel("txn" to "t1")
        assertEquals("t1", (vm.detail.value as DetailState.Loaded).full.id)
    }

    @Test
    fun categorisingOffersTheSuggestionAndAcceptingConfirmsTheRow() {
        val vm = viewModel()
        vm.open(coffee)
        vm.startCategorising()
        val editor = vm.editor.value
        assertEquals(listOf("dining", "groceries"), editor.categories)
        assertEquals("dining", editor.suggestion?.category)

        vm.acceptSuggestion(editor.suggestion!!)
        val (id, changes) = ledger.edits.single()
        assertEquals("t1", id)
        assertEquals(Patch.Set("dining"), changes.category)
        assertEquals(Patch.Set("coffee"), changes.subcategory)
        assertEquals(Patch.Set("Blue Bottle"), changes.merchant)
        assertEquals(Patch.Set("confirmed"), changes.reviewState)
        assertNull(vm.editor.value.suggestion)
    }

    @Test
    fun pickingACategoryByHandClearsTheSubcategory() {
        val vm = viewModel()
        vm.open(coffee)
        vm.setCategory("groceries")
        val changes = ledger.edits.single().second
        assertEquals(Patch.Clear, changes.subcategory)
        assertEquals("""{"category":"groceries","subcategory":null,"review_state":"confirmed"}""", changes.toJson().toString())
    }

    @Test
    fun notesClearWithNullAndUnchangedFieldsSendNothing() {
        ledger.transactions = listOf(coffee.copy(notes = "work"))
        val vm = viewModel()
        vm.open(ledger.transactions.single())
        vm.setMerchant("")
        assertTrue("an empty merchant on a row without one is not a change", ledger.edits.isEmpty())
        vm.setNotes("  ")
        assertEquals(Patch.Clear, ledger.edits.single().second.notes)
    }

    @Test
    fun tagsAreAddedTrimmedAndOnlyOnce() {
        ledger.transactions = listOf(coffee.copy(tags = listOf("work")))
        val vm = viewModel()
        vm.open(ledger.transactions.single())
        vm.addTag("work")
        vm.addTag("  trip ")
        vm.removeTag("work")
        assertEquals(listOf(Triple("t1", "trip", true), Triple("t1", "work", false)), ledger.tagEdits)
    }
}
