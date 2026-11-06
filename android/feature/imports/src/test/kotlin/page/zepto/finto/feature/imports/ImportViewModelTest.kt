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
import page.zepto.finto.core.testing.FakeAdminRepository
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
    private val uri = Uri.parse("content://downloads/statement.pdf")

    @Test
    fun previewThenConfirmSendsThePreviewHash() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
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
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertTrue(repository.confirmed.isEmpty())
    }

    @Test
    fun aSharedFileIsPreviewedOnce() {
        val incoming = IncomingFiles().apply { offer(uri) }
        val viewModel = ImportViewModel(repository, incoming, admin)
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertEquals(null, incoming.file.value)
    }

    @Test
    fun aFailedPreviewIsReportedAndCleanedUp() {
        repository.previewError = java.io.IOException("offline")
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
        viewModel.onFileChosen(uri)
        assertTrue(viewModel.state.value.step is ImportStep.Failed)
        assertEquals(1, repository.discarded.size)
    }

    @Test
    fun importingRunsTheReviewAndRefreshesHistory() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
        assertEquals(listOf("sf-0"), viewModel.state.value.history.map { it.id })
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertEquals(ReviewLoad.Done(review), viewModel.state.value.review)
        assertEquals(8, ImportLabels.categorised(review))
        assertEquals("89%", ImportLabels.share(8, 9))
    }

    @Test
    fun historyReviewsOpenAndCloseOnTap() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
        val file = viewModel.state.value.history.single()
        viewModel.toggleHistoryReview(file)
        assertEquals("sf-0", viewModel.state.value.historyReviewId)
        assertEquals(40, (viewModel.state.value.historyReview as ReviewLoad.Done).review.statement.rows)
        viewModel.toggleHistoryReview(file)
        assertEquals(null, viewModel.state.value.historyReviewId)
    }

    @Test
    fun maintenanceReportsItsCounts() {
        val viewModel = ImportViewModel(repository, IncomingFiles(), admin)
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
}
