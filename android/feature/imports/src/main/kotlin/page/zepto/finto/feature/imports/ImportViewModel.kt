package page.zepto.finto.feature.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.IncomingFiles
import page.zepto.finto.core.data.repository.ImportRepository
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.ImportFormat
import page.zepto.finto.core.model.ImportResult
import page.zepto.finto.core.model.StagePreview
import javax.inject.Inject

/**
 * Preview first, then confirm: nothing reaches the ledger until the person has
 * seen what the parser read. A wrong column mapping is glaring in five parsed
 * rows and invisible six months later.
 */
sealed interface ImportStep {
    data object Idle : ImportStep
    data class Reading(val name: String?) : ImportStep
    data class Previewing(val file: StagedFile) : ImportStep
    data class Ready(val file: StagedFile, val preview: StagePreview) : ImportStep
    data class Importing(val file: StagedFile, val preview: StagePreview) : ImportStep
    data class Done(val preview: StagePreview, val result: ImportResult) : ImportStep
    data class Failed(val message: String, val preview: StagePreview? = null) : ImportStep
}

data class ImportUiState(
    val step: ImportStep = ImportStep.Idle,
    val formats: List<ImportFormat> = emptyList(),
    val extensions: List<String> = emptyList(),
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val imports: ImportRepository,
    private val incoming: IncomingFiles,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            suspendRunCatching { imports.capabilities() }.onSuccess { caps ->
                mutableState.update { it.copy(formats = caps.formats, extensions = caps.extensions) }
            }
        }
        viewModelScope.launch {
            incoming.file.filterNotNull().collect {
                incoming.take()?.let(::onFileChosen)
            }
        }
    }

    fun onFileChosen(uri: Uri) {
        val step = mutableState.value.step
        if (step is ImportStep.Previewing || step is ImportStep.Importing) return
        discardCurrent()
        mutableState.update { it.copy(step = ImportStep.Reading(uri.lastPathSegment)) }
        viewModelScope.launch {
            val staged = suspendRunCatching { imports.stage(uri) }.getOrElse { error ->
                mutableState.update { it.copy(step = ImportStep.Failed(ApiErrors.message(error))) }
                return@launch
            }
            mutableState.update { it.copy(step = ImportStep.Previewing(staged)) }
            suspendRunCatching { imports.preview(staged) }
                .onSuccess { preview ->
                    mutableState.update { it.copy(step = ImportStep.Ready(staged, preview)) }
                }
                .onFailure { error ->
                    imports.discard(staged)
                    mutableState.update { it.copy(step = ImportStep.Failed(ApiErrors.message(error))) }
                }
        }
    }

    fun confirm() {
        val ready = mutableState.value.step as? ImportStep.Ready ?: return
        if (ready.preview.error != null) return
        mutableState.update { it.copy(step = ImportStep.Importing(ready.file, ready.preview)) }
        viewModelScope.launch {
            suspendRunCatching { imports.confirm(ready.file, ready.preview.sha256) }
                .onSuccess { result ->
                    imports.discard(ready.file)
                    mutableState.update { it.copy(step = ImportStep.Done(ready.preview, result)) }
                }
                .onFailure { error ->
                    imports.discard(ready.file)
                    mutableState.update {
                        it.copy(step = ImportStep.Failed(ApiErrors.message(error), ready.preview))
                    }
                }
        }
    }

    fun reset() {
        discardCurrent()
        mutableState.update { it.copy(step = ImportStep.Idle) }
    }

    private fun discardCurrent() {
        val file = when (val step = mutableState.value.step) {
            is ImportStep.Ready -> step.file
            else -> null
        } ?: return
        viewModelScope.launch { imports.discard(file) }
    }

    override fun onCleared() {
        discardCurrent()
    }
}
