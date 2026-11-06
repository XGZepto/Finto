package page.zepto.finto.feature.investments

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ApplicationScope
import page.zepto.finto.core.data.repository.ImportRepository
import page.zepto.finto.core.data.repository.InvestmentsRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.MpfBundlePreview
import page.zepto.finto.core.model.MpfImportResult
import javax.inject.Inject

/**
 * The whole bundle is validated before anything is saved: a snapshot is
 * only right when every statement in it reconciles with the others.
 */
sealed interface MpfStep {
    data object Idle : MpfStep
    data class Validating(val count: Int) : MpfStep
    data class Ready(val files: List<StagedFile>, val preview: MpfBundlePreview, val error: String? = null) : MpfStep
    data class Importing(val files: List<StagedFile>, val preview: MpfBundlePreview) : MpfStep
    data class Done(val preview: MpfBundlePreview, val result: MpfImportResult) : MpfStep
    data class Failed(val message: String) : MpfStep
}

data class MpfImportUiState(
    val step: MpfStep = MpfStep.Idle,
    val accountNames: Map<String, String> = emptyMap(),
) {
    fun accountLabel(id: String): String = accountNames[id] ?: Investments.friendly(id)
}

@HiltViewModel
class MpfImportViewModel @Inject constructor(
    private val investments: InvestmentsRepository,
    private val imports: ImportRepository,
    private val ledger: LedgerRepository,
    /** Staged copies are deleted even after the screen is gone. */
    @param:ApplicationScope private val cleanup: CoroutineScope,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MpfImportUiState())
    val state: StateFlow<MpfImportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            suspendRunCatching { ledger.accounts() }.onSuccess { accounts ->
                mutableState.update { it.copy(accountNames = accounts.associate { a -> a.id to a.displayName }) }
            }
        }
    }

    fun onFilesChosen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val step = mutableState.value.step
        if (step is MpfStep.Validating || step is MpfStep.Importing) return
        discardCurrent()
        if (uris.size > MAX_FILES) {
            mutableState.update { it.copy(step = MpfStep.Failed("Choose between 1 and $MAX_FILES PDFs.")) }
            return
        }
        mutableState.update { it.copy(step = MpfStep.Validating(uris.size)) }
        viewModelScope.launch {
            val staged = mutableListOf<StagedFile>()
            for (uri in uris) {
                suspendRunCatching { imports.stage(uri) }
                    .onSuccess { staged += it }
                    .onFailure { error ->
                        discard(staged)
                        mutableState.update { it.copy(step = MpfStep.Failed(ApiErrors.message(error))) }
                        return@launch
                    }
            }
            suspendRunCatching { investments.previewBundle(staged) }
                .onSuccess { preview -> mutableState.update { it.copy(step = MpfStep.Ready(staged, preview)) } }
                .onFailure { error ->
                    discard(staged)
                    mutableState.update { it.copy(step = MpfStep.Failed(ApiErrors.message(error))) }
                }
        }
    }

    fun confirm() {
        val ready = mutableState.value.step as? MpfStep.Ready ?: return
        mutableState.update { it.copy(step = MpfStep.Importing(ready.files, ready.preview)) }
        viewModelScope.launch {
            suspendRunCatching { investments.confirmBundle(ready.files, ready.preview.bundleSha256) }
                .onSuccess { result ->
                    discard(ready.files)
                    mutableState.update { it.copy(step = MpfStep.Done(ready.preview, result)) }
                }
                .onFailure { error ->
                    // Keep the files: a dropped connection is worth retrying without picking them again.
                    mutableState.update { it.copy(step = ready.copy(error = ApiErrors.message(error))) }
                }
        }
    }

    /** Back to choosing files, dropping any staged ones. */
    fun reset() {
        discardCurrent()
        mutableState.update { it.copy(step = MpfStep.Idle) }
    }

    private fun discardCurrent() {
        when (val step = mutableState.value.step) {
            is MpfStep.Ready -> discard(step.files)
            is MpfStep.Importing -> discard(step.files)
            else -> Unit
        }
    }

    private fun discard(files: List<StagedFile>) {
        if (files.isEmpty()) return
        val copies = files.toList()
        cleanup.launch { copies.forEach { file -> suspendRunCatching { imports.discard(file) } } }
    }

    override fun onCleared() {
        discardCurrent()
    }

    companion object {
        const val MAX_FILES = 12
    }
}
