package page.zepto.finto.feature.investments

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import page.zepto.finto.core.model.MpfActivityResult
import page.zepto.finto.core.model.MpfImportResult
import page.zepto.finto.core.model.MpfSnapshotStatus
import page.zepto.finto.core.testing.FakeImportRepository
import page.zepto.finto.core.testing.FakeInvestmentsRepository
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MpfImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val investments = FakeInvestmentsRepository().apply {
        result = MpfImportResult(bundleSha256 = "bundle123", snapshot = MpfSnapshotStatus("imported", "snap-1"), activities = MpfActivityResult(inserted = 14, skipped = 2))
    }
    private val imports = FakeImportRepository()
    private val uris = (1..5).map { Uri.parse("content://downloads/mpf-$it.pdf") }

    private fun viewModel() = MpfImportViewModel(investments, imports, FakeLedgerRepository(), CoroutineScope(main.dispatcher))

    @Test
    fun stagesEveryFileThenPreviewsTheBundle() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        val ready = viewModel.state.value.step as MpfStep.Ready
        assertEquals(5, ready.files.size)
        assertEquals("bundle123", ready.preview.bundleSha256)
        assertTrue(imports.discarded.isEmpty())
    }

    @Test
    fun confirmSendsThePreviewHashAndDropsTheStagedCopies() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        viewModel.confirm()
        val done = viewModel.state.value.step as MpfStep.Done
        assertEquals("imported", done.result.snapshot.status)
        assertEquals(14, done.result.activities.inserted)
        assertEquals(listOf("bundle123"), investments.confirmed)
        assertEquals(5, imports.discarded.size)
    }

    @Test
    fun moreThanTwelvePdfsAreRefusedBeforeAnythingIsStaged() {
        val viewModel = viewModel()
        viewModel.onFilesChosen((1..13).map { Uri.parse("content://downloads/$it.pdf") })
        val failed = viewModel.state.value.step as MpfStep.Failed
        assertEquals("Choose between 1 and 12 PDFs.", failed.message)
        assertTrue(investments.confirmed.isEmpty())
    }

    @Test
    fun noFilesChosenChangesNothing() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(emptyList())
        assertEquals(MpfStep.Idle, viewModel.state.value.step)
    }

    @Test
    fun aRejectedPreviewShowsTheServerDetailAndCleansUp() {
        investments.failure = HttpException(
            Response.error<Any>(422, """{"detail":"member returns statement is missing"}""".toResponseBody()),
        )
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        val failed = viewModel.state.value.step as MpfStep.Failed
        assertEquals("Member returns statement is missing", failed.message)
        assertEquals(5, imports.discarded.size)
    }

    @Test
    fun aFailedConfirmKeepsTheFilesToRetry() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        investments.failure = IOException("offline")
        viewModel.confirm()
        val ready = viewModel.state.value.step as MpfStep.Ready
        assertNotNull(ready.error)
        assertTrue(imports.discarded.isEmpty())

        investments.failure = null
        viewModel.confirm()
        assertTrue(viewModel.state.value.step is MpfStep.Done)
    }

    @Test
    fun aMismatchedBundleReportsTheConflict() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        investments.failure = HttpException(
            Response.error<Any>(409, """{"detail":"MPF bundle does not match the preview SHA-256"}""".toResponseBody()),
        )
        viewModel.confirm()
        assertEquals("MPF bundle does not match the preview SHA-256", (viewModel.state.value.step as MpfStep.Ready).error)
    }

    @Test
    fun startingOverDiscardsTheStagedFiles() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        viewModel.reset()
        assertEquals(MpfStep.Idle, viewModel.state.value.step)
        assertEquals(5, imports.discarded.size)
    }

    @Test
    fun choosingOtherFilesDiscardsTheFirstSet() {
        val viewModel = viewModel()
        viewModel.onFilesChosen(uris)
        viewModel.onFilesChosen(uris.take(2))
        assertEquals(5, imports.discarded.size)
        assertEquals(2, (viewModel.state.value.step as MpfStep.Ready).files.size)
    }
}
