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
import kotlinx.coroutines.flow.catch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.AdminRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Institution
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ImportedFile
import page.zepto.finto.core.data.IncomingFiles
import page.zepto.finto.core.data.repository.ImportRepository
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.ImportFormat
import page.zepto.finto.core.model.ImportResult
import page.zepto.finto.core.model.ReconcileSummary
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
    data class Done(
        val preview: StagePreview,
        val result: ImportResult,
        val reconcile: ReconcileSummary? = null,
    ) : ImportStep
    data class Failed(val message: String, val preview: StagePreview? = null) : ImportStep
}

/** The post-import pass (categories, tags, links) for one statement. */
sealed interface ReviewLoad {
    data object Idle : ReviewLoad
    data object Running : ReviewLoad
    data class Done(val review: ImportReview) : ReviewLoad
    data class Failed(val message: String) : ReviewLoad
}

enum class Maintenance(val label: String) {
    Reconcile("Reconcile ledger"),
    Reattribute("Re-attribute cards"),
    HarvestFx("Harvest FX rates"),
}

data class MaintenanceState(
    val job: Maintenance,
    val running: Boolean = true,
    /** Non-zero counts from the result, labelled as the web labels them. */
    val changes: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
)

data class ImportUiState(
    val step: ImportStep = ImportStep.Idle,
    val formats: List<ImportFormat> = emptyList(),
    val extensions: List<String> = emptyList(),
    val history: List<ImportedFile> = emptyList(),
    val historyLoading: Boolean = true,
    /** The review of the statement just imported. */
    val review: ReviewLoad = ReviewLoad.Idle,
    /** The imported file whose review is open in the history, and that review. */
    val historyReviewId: String? = null,
    val historyReview: ReviewLoad = ReviewLoad.Idle,
    val maintenance: MaintenanceState? = null,
    /** Every account, for filing a statement the server can't route itself. */
    val accounts: List<Account> = emptyList(),
    val institutions: List<Institution> = emptyList(),
    /** The account the person chose for rows the statement doesn't name. */
    val chosenAccount: String? = null,
    /** True while the preview is re-checked against the chosen account. */
    val rechecking: Boolean = false,
    /** The new-account form, while it is open. */
    val draft: AccountDraft? = null,
    val creatingAccount: Boolean = false,
    val accountError: String? = null,
) {
    /** The chosen account, if the person picked one. */
    val chosen: Account? get() = chosenAccount?.let { id -> accounts.firstOrNull { it.id == id } }
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val imports: ImportRepository,
    private val incoming: IncomingFiles,
    private val admin: AdminRepository,
    private val ledger: LedgerRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            suspendRunCatching { imports.capabilities() }.onSuccess { caps ->
                mutableState.update { it.copy(formats = caps.formats, extensions = caps.extensions) }
            }
        }
        loadHistory()
        loadAccounts()
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
        mutableState.update { it.forNewFile().copy(step = ImportStep.Reading(uri.lastPathSegment)) }
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
        val state = mutableState.value
        val ready = state.step as? ImportStep.Ready ?: return
        if (ready.preview.error != null || state.rechecking) return
        if (ready.preview.needsAccount && state.chosenAccount == null) return
        mutableState.update { it.copy(step = ImportStep.Importing(ready.file, ready.preview)) }
        viewModelScope.launch {
            suspendRunCatching { imports.confirm(ready.file, ready.preview.sha256, state.chosenAccount) }
                .onSuccess { response ->
                    imports.discard(ready.file)
                    mutableState.update { it.copy(step = ImportStep.Done(ready.preview, response.result, response.reconcile)) }
                    val statement = response.result.statementFileId
                    if (response.result.status == "imported" && statement != null) runReview(statement)
                    loadHistory(ReadPolicy.Network)
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
        mutableState.update { it.forNewFile().copy(step = ImportStep.Idle, review = ReviewLoad.Idle) }
    }

    /**
     * Files the rows the statement doesn't route under [accountId] and checks
     * the preview again. The model's verdict is dropped rather than re-asked:
     * it was about the old routing, and the checks below it say the rest.
     */
    fun chooseAccount(accountId: String) {
        val ready = mutableState.value.step as? ImportStep.Ready ?: return
        mutableState.update { it.copy(chosenAccount = accountId, rechecking = true, accountError = null) }
        viewModelScope.launch {
            suspendRunCatching { imports.preview(ready.file, review = false, accountId = accountId) }
                .onSuccess { preview ->
                    mutableState.update {
                        val current = it.step as? ImportStep.Ready
                        if (current?.file != ready.file) it
                        else it.copy(step = ImportStep.Ready(ready.file, preview), rechecking = false)
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(rechecking = false, accountError = ApiErrors.message(error)) }
                }
        }
    }

    /** Opens the new-account form, filled in from the statement being previewed. */
    fun startNewAccount() {
        val ready = mutableState.value.step as? ImportStep.Ready ?: return
        mutableState.update { it.copy(draft = AccountDrafts.from(ready.preview, it.institutions), accountError = null) }
    }

    fun updateDraft(draft: AccountDraft) = mutableState.update { it.copy(draft = draft) }

    fun cancelDraft() = mutableState.update { it.copy(draft = null, accountError = null) }

    /** Creates the drafted account, then files the statement under it. */
    fun createAccount() {
        val state = mutableState.value
        val draft = state.draft?.takeIf { it.isComplete } ?: return
        if (state.creatingAccount) return
        mutableState.update { it.copy(creatingAccount = true, accountError = null) }
        viewModelScope.launch {
            suspendRunCatching { ledger.createAccount(AccountDrafts.toNewAccount(draft, state.accounts)) }
                .onSuccess { account ->
                    mutableState.update {
                        it.copy(accounts = it.accounts + account, draft = null, creatingAccount = false)
                    }
                    chooseAccount(account.id)
                }
                .onFailure { error ->
                    mutableState.update { it.copy(creatingAccount = false, accountError = ApiErrors.message(error)) }
                }
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            suspendRunCatching { ledger.accounts() }.onSuccess { accounts ->
                mutableState.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            suspendRunCatching { ledger.institutions() }.onSuccess { institutions ->
                mutableState.update { it.copy(institutions = institutions) }
            }
        }
    }

    private fun ImportUiState.forNewFile() =
        copy(chosenAccount = null, rechecking = false, draft = null, creatingAccount = false, accountError = null)

    fun refreshHistory() = loadHistory(ReadPolicy.Network)

    private fun loadHistory(policy: ReadPolicy? = null) {
        viewModelScope.launch {
            val reads = if (policy == null) staleWhileRevalidate { admin.importHistory(it) } else kotlinx.coroutines.flow.flowOf(admin.importHistory(policy))
            reads.catch { mutableState.update { it.copy(historyLoading = false) } }
                .collect { history -> mutableState.update { it.copy(history = history.files, historyLoading = false) } }
        }
    }

    /** Runs the review pass for the statement just imported. */
    fun runReview(statementFileId: String) {
        mutableState.update { it.copy(review = ReviewLoad.Running) }
        viewModelScope.launch {
            val outcome = suspendRunCatching { admin.reviewImport(statementFileId) }
                .fold({ ReviewLoad.Done(it) }, { ReviewLoad.Failed(ApiErrors.message(it)) })
            mutableState.update { it.copy(review = outcome) }
        }
    }

    /** Shows a past file's last review, running one if it never had any; tapping again closes it. */
    fun toggleHistoryReview(file: ImportedFile) {
        if (mutableState.value.historyReviewId == file.id) {
            mutableState.update { it.copy(historyReviewId = null, historyReview = ReviewLoad.Idle) }
            return
        }
        mutableState.update { it.copy(historyReviewId = file.id, historyReview = ReviewLoad.Running) }
        viewModelScope.launch {
            val outcome = suspendRunCatching { admin.lastImportReview(file.id) ?: admin.reviewImport(file.id) }
                .fold({ ReviewLoad.Done(it) }, { ReviewLoad.Failed(ApiErrors.message(it)) })
            if (mutableState.value.historyReviewId == file.id) mutableState.update { it.copy(historyReview = outcome) }
        }
    }

    fun rerunHistoryReview() {
        val id = mutableState.value.historyReviewId ?: return
        mutableState.update { it.copy(historyReview = ReviewLoad.Running) }
        viewModelScope.launch {
            val outcome = suspendRunCatching { admin.reviewImport(id) }
                .fold({ ReviewLoad.Done(it) }, { ReviewLoad.Failed(ApiErrors.message(it)) })
            if (mutableState.value.historyReviewId == id) mutableState.update { it.copy(historyReview = outcome) }
            loadHistory(ReadPolicy.Network)
        }
    }

    fun runMaintenance(job: Maintenance) {
        if (mutableState.value.maintenance?.running == true) return
        mutableState.update { it.copy(maintenance = MaintenanceState(job)) }
        viewModelScope.launch {
            suspendRunCatching {
                when (job) {
                    Maintenance.Reconcile -> admin.reconcile()
                    Maintenance.Reattribute -> admin.reattribute()
                    Maintenance.HarvestFx -> admin.harvestFx()
                }
            }
                .onSuccess { result -> mutableState.update { it.copy(maintenance = MaintenanceState(job, running = false, changes = ImportLabels.counts(result))) } }
                .onFailure { error -> mutableState.update { it.copy(maintenance = MaintenanceState(job, running = false, error = ApiErrors.message(error))) } }
        }
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
