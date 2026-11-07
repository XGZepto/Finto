package page.zepto.finto.feature.imports

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import page.zepto.finto.core.data.IncomingFiles
import page.zepto.finto.core.model.ImportHistory
import page.zepto.finto.core.model.ImportResult
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ImportedFile
import page.zepto.finto.core.model.ReviewCategories
import page.zepto.finto.core.model.ReviewSummary
import page.zepto.finto.core.model.ReviewedStatement
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.ImportAgent
import page.zepto.finto.core.model.ImportQuality
import page.zepto.finto.core.model.InOut
import page.zepto.finto.core.model.Institution
import page.zepto.finto.core.model.QualityCheck
import page.zepto.finto.core.model.StatementCard
import page.zepto.finto.core.testing.FakeAdminRepository
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeImportRepository
import page.zepto.finto.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeImportRepository(result = ImportResult(status = "imported", txns = 9, statementFileId = "sf-1"))
    private val review = ImportReview(
        statement = ReviewedStatement("sf-1", rows = 9),
        categories = ReviewCategories(bySource = mapOf("rules" to 6, "model" to 2, "uncategorised" to 1)),
        summary = ReviewSummary("attention", "1 row needs a category"),
    )
    private val admin = FakeAdminRepository().apply {
        history = ImportHistory(listOf(ImportedFile("sf-0", "/x/2026__Mox.pdf", txnCount = 40, reviewStatus = "clean")))
        reviews["sf-1"] = review
        reviews["sf-0"] = review.copy(statement = ReviewedStatement("sf-0", rows = 40))
    }
    private val ledger = FakeLedgerRepository().apply {
        accounts = listOf(Account("hsbc_hk_current", "hsbc_hk", "HSBC Current", "checking", "HKD"))
        institutions = listOf(Institution("hsbc_hk", "HSBC"), Institution("mox", "Mox"))
    }
    private val uri = Uri.parse("content://downloads/statement.pdf")

    /** A card statement for a card the ledger has no account for. */
    private val unrouted = StagePreview(
        sha256 = "red",
        filename = "eStatement.pdf",
        txnCount = 4,
        totals = mapOf("HKD" to InOut(inflow = 760216, out = -760216, rows = 4)),
        quality = ImportQuality("bad", listOf(QualityCheck("account", "bad", "No account for 4 of 4 rows"))),
        agent = ImportAgent(verdict = "hold", headline = "Choose the account first"),
        institutionHint = "hsbc_hk",
        cards = listOf(StatementCard("4242", "ALEX SAMPLE", rows = 4)),
    )

    private fun routedTo(name: String) = unrouted.copy(
        quality = ImportQuality("ok", listOf(QualityCheck("account", "ok", "Goes to $name"))),
        agent = null,
    )

    @Test
    fun previewThenConfirmSendsThePreviewHash() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        viewModel.confirm()
        val done = viewModel.state.value.step as ImportStep.Done
        assertEquals(9, done.result.txns)
        assertEquals(listOf("abc123"), repository.confirmed)
        assertEquals(1, repository.discarded.size)
    }

    @Test
    fun aPreviewWithAnErrorCannotBeConfirmed() {
        repository.preview = StagePreview(sha256 = "x", filename = "scan.pdf", error = "Parsed zero transactions.")
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertTrue(repository.confirmed.isEmpty())
    }

    @Test
    fun aSharedFileIsPreviewedOnce() {
        val incoming = IncomingFiles().apply { offer(uri) }
        val viewModel = ImportViewModel(repository, incoming, admin, ledger)
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertEquals(null, incoming.file.value)
    }

    @Test
    fun aFailedPreviewIsReportedAndCleanedUp() {
        repository.previewError = java.io.IOException("offline")
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        assertTrue(viewModel.state.value.step is ImportStep.Failed)
        assertEquals(1, repository.discarded.size)
    }

    @Test
    fun importingRunsTheReviewAndRefreshesHistory() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        assertEquals(listOf("sf-0"), viewModel.state.value.history.map { it.id })
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertEquals(ReviewLoad.Done(review), viewModel.state.value.review)
        assertEquals(8, ImportLabels.categorised(review))
        assertEquals("89%", ImportLabels.share(8, 9))
    }

    @Test
    fun historyReviewsOpenAndCloseOnTap() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        val file = viewModel.state.value.history.single()
        viewModel.toggleHistoryReview(file)
        assertEquals("sf-0", viewModel.state.value.historyReviewId)
        assertEquals(40, (viewModel.state.value.historyReview as ReviewLoad.Done).review.statement.rows)
        viewModel.toggleHistoryReview(file)
        assertEquals(null, viewModel.state.value.historyReviewId)
    }

    @Test
    fun maintenanceReportsItsCounts() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.runMaintenance(Maintenance.Reconcile)
        assertEquals(listOf("reconcile"), admin.maintenance)
        val state = viewModel.state.value.maintenance!!
        assertEquals(false, state.running)
        assertEquals(emptyList<Pair<String, String>>(), state.changes)
        assertEquals(
            listOf("Transfers linked" to "3"),
            ImportLabels.counts(kotlinx.serialization.json.buildJsonObject {
                put("transfers_linked", kotlinx.serialization.json.JsonPrimitive(3))
                put("duplicates_merged", kotlinx.serialization.json.JsonPrimitive(0))
                put("ok", kotlinx.serialization.json.JsonPrimitive(true))
            }),
        )
    }

    @Test
    fun aStatementWithNoAccountWaitsUntilOneIsChosen() {
        repository.preview = unrouted
        repository.previewFor = { id -> if (id == null) unrouted else routedTo("HSBC Current") }
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertTrue("no account yet, so nothing is sent", repository.confirmed.isEmpty())

        viewModel.chooseAccount("hsbc_hk_current")
        val ready = viewModel.state.value.step as ImportStep.Ready
        assertEquals(listOf(null, "hsbc_hk_current"), repository.previewAccounts)
        assertEquals(false, ready.preview.needsAccount)
        assertEquals("the verdict about the old routing is gone", null, ready.preview.agent)
        assertEquals("HSBC Current", viewModel.state.value.chosen?.displayName)

        viewModel.confirm()
        assertEquals(listOf<String?>("hsbc_hk_current"), repository.confirmedAccounts)
    }

    @Test
    fun aNewAccountIsFilledInFromTheStatementAndUsed() {
        repository.preview = unrouted
        repository.previewFor = { id -> if (id == null) unrouted else routedTo("HSBC card ··4242") }
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        viewModel.startNewAccount()
        val draft = viewModel.state.value.draft!!
        assertEquals(AccountDraft("HSBC card ··4242", "hsbc_hk", "credit_card", "HKD", "4242", "ALEX SAMPLE"), draft)

        viewModel.updateDraft(draft.copy(displayName = "HSBC Red"))
        viewModel.createAccount()
        val created = ledger.createdAccounts.single()
        assertEquals("hsbc_hk_card_4242", created.id)
        assertEquals("HSBC Red", created.displayName)
        assertEquals("4242", created.last4)
        assertEquals("ALEX SAMPLE", created.cardholderName)
        val state = viewModel.state.value
        assertEquals(null, state.draft)
        assertEquals("hsbc_hk_card_4242", state.chosenAccount)
        assertEquals("hsbc_hk_card_4242", repository.previewAccounts.last())
        viewModel.confirm()
        assertEquals(listOf<String?>("hsbc_hk_card_4242"), repository.confirmedAccounts)
    }

    @Test
    fun aFailedCreateKeepsTheFormOpenWithTheReason() {
        repository.preview = unrouted
        ledger.createFailure = IllegalStateException("Unknown institution")
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        viewModel.startNewAccount()
        viewModel.createAccount()
        val state = viewModel.state.value
        assertTrue(state.draft != null)
        assertEquals("Unknown institution", state.accountError)
        assertEquals(null, state.chosenAccount)
    }

    @Test
    fun aNewFileForgetsTheLastChoice() {
        repository.preview = unrouted
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin, ledger)
        viewModel.onFileChosen(uri)
        viewModel.chooseAccount("hsbc_hk_current")
        viewModel.reset()
        viewModel.onFileChosen(uri)
        assertEquals(null, viewModel.state.value.chosenAccount)
    }
}
