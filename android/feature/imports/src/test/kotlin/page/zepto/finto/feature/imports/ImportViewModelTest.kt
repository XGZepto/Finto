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
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.testing.FakeImportRepository
import page.zepto.finto.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeImportRepository()
    private val uri = Uri.parse("content://downloads/statement.pdf")

    @Test
    fun previewThenConfirmSendsThePreviewHash() {
        val viewModel = ImportViewModel(repository, IncomingFiles())
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
        val viewModel = ImportViewModel(repository, IncomingFiles())
        viewModel.onFileChosen(uri)
        viewModel.confirm()
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertTrue(repository.confirmed.isEmpty())
    }

    @Test
    fun aSharedFileIsPreviewedOnce() {
        val incoming = IncomingFiles().apply { offer(uri) }
        val viewModel = ImportViewModel(repository, incoming)
        assertTrue(viewModel.state.value.step is ImportStep.Ready)
        assertEquals(null, incoming.file.value)
    }

    @Test
    fun aFailedPreviewIsReportedAndCleanedUp() {
        repository.previewError = java.io.IOException("offline")
        val viewModel = ImportViewModel(repository, IncomingFiles())
        viewModel.onFileChosen(uri)
        assertTrue(viewModel.state.value.step is ImportStep.Failed)
        assertEquals(1, repository.discarded.size)
    }
}
