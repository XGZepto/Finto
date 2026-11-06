package page.zepto.finto.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.CategorySuggestion
import page.zepto.finto.core.model.Patch
import page.zepto.finto.core.model.TransactionChanges
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Txn
import java.time.LocalDate
import javax.inject.Inject

/** One line of the blotter: a day header or a transaction. */
sealed interface ActivityItem {
    data class Day(val date: LocalDate) : ActivityItem
    data class Row(val txn: Txn) : ActivityItem
}

data class AccountChoice(val id: String, val name: String)

data class ActivityUiState(
    val title: String = "Activity",
    val subtitle: String? = null,
    val scoped: Boolean = false,
    val query: String = "",
    val spendingOnly: Boolean = false,
    val account: AccountChoice? = null,
    val accounts: List<AccountChoice> = emptyList(),
    val accountLocked: Boolean = false,
)

/** The detail sheet's editing side: the taxonomy, the model's guess, and save progress. */
data class EditorState(
    val categories: List<String> = emptyList(),
    val suggestion: CategorySuggestion? = null,
    val suggestionLoading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface DetailState {
    val summary: Txn

    data class Loading(override val summary: Txn) : DetailState
    data class Loaded(override val summary: Txn, val full: Txn) : DetailState
    data class Failed(override val summary: Txn, val message: String) : DetailState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: LedgerRepository,
) : ViewModel() {

    // Route properties are stored under their own names; reading them directly
    // keeps this testable on the JVM, where there is no Bundle behind toRoute().
    private val route = ActivityDestination(
        filter = savedStateHandle.get<String>("filter"),
        title = savedStateHandle.get<String>("title"),
        txn = savedStateHandle.get<String>("txn"),
    )
    private val scope: LedgerFilter = route.scope

    private val query = MutableStateFlow("")
    private val spendingOnly = MutableStateFlow(scope.spendingOnly)
    private val account = MutableStateFlow<String?>(null)
    private val accounts = MutableStateFlow<List<AccountChoice>>(emptyList())
    private val detailState = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = detailState.asStateFlow()
    private val editorState = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = editorState.asStateFlow()

    /** Rows edited here, laid over the paged list so it shows the change without a reload. */
    private val edited = MutableStateFlow<Map<String, Txn>>(emptyMap())

    private val locked = scope.accounts.isNotEmpty()

    val state: StateFlow<ActivityUiState> = combine(query, spendingOnly, account, accounts) { q, spending, acct, all ->
        val shown = if (locked) scope.accounts.singleOrNull() else acct
        ActivityUiState(
            title = route.title ?: "Activity",
            subtitle = describe(scope),
            scoped = route.isScoped,
            query = q,
            spendingOnly = spending,
            account = shown?.let { id -> all.firstOrNull { it.id == id } ?: AccountChoice(id, id) },
            accounts = all,
            accountLocked = locked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(scoped = route.isScoped))

    private val filter: Flow<LedgerFilter> = combine(
        query.debounce { if (it.isEmpty()) 0 else 350 },
        spendingOnly,
        account,
    ) { q, spending, acct ->
        scope.copy(
            accounts = if (locked) scope.accounts else listOfNotNull(acct),
            query = q.trim().ifEmpty { null } ?: scope.query,
            spendingOnly = spending,
        )
    }.distinctUntilChanged()

    val items: Flow<PagingData<ActivityItem>> = filter
        .flatMapLatest { ledger.transactions(it) }
        .cachedIn(viewModelScope)
        .combine(edited) { paging, overrides -> paging.map { overrides[it.id] ?: it } }
        .map { paging ->
            paging.map<Txn, ActivityItem> { ActivityItem.Row(it) }
                .insertSeparators { before, after ->
                    val next = (after as? ActivityItem.Row)?.txn?.date?.let(DisplayDates::parse)
                        ?: return@insertSeparators null
                    val previous = (before as? ActivityItem.Row)?.txn?.date?.let(DisplayDates::parse)
                    if (previous != next) ActivityItem.Day(next) else null
                }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            route.txn?.let { openById(it) }
            suspendRunCatching { ledger.accounts() }.onSuccess { list ->
                accounts.value = list.filter { it.closedOn == null || it.id in scope.accounts }.map(Account::toChoice)
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSpendingOnlyChange(value: Boolean) {
        spendingOnly.value = value
    }

    fun onAccountChange(choice: AccountChoice?) {
        if (!locked) account.value = choice?.id
    }

    fun open(txn: Txn) {
        detailState.value = DetailState.Loading(txn)
        viewModelScope.launch {
            detailState.value = suspendRunCatching { ledger.transaction(txn.id) }.fold(
                onSuccess = { DetailState.Loaded(txn, it) },
                onFailure = { DetailState.Failed(txn, ApiErrors.message(it)) },
            )
        }
    }

    /** Opens a transaction known only by id: the sheet shows once it has loaded. */
    private suspend fun openById(id: String) {
        suspendRunCatching { ledger.transaction(id) }.onSuccess { full -> detailState.value = DetailState.Loaded(full, full) }
    }

    fun closeDetail() {
        detailState.value = null
        editorState.value = editorState.value.copy(suggestion = null, suggestionLoading = false, error = null)
    }

    /** Opens a linked row (a transfer leg, a refunded purchase) in place of the current one. */
    fun openLinked(id: String) {
        viewModelScope.launch { openById(id) }
    }

    /** Loads the category list and the model's guess for the open row. */
    fun startCategorising() {
        val txn = currentTxn() ?: return
        viewModelScope.launch {
            if (editorState.value.categories.isEmpty()) {
                suspendRunCatching { ledger.facets() }.onSuccess { facets ->
                    editorState.value = editorState.value.copy(categories = facets.categories.sorted())
                }
            }
        }
        editorState.value = editorState.value.copy(suggestion = null, suggestionLoading = true)
        viewModelScope.launch {
            val guess = suspendRunCatching { ledger.categorySuggestion(txn.id) }.getOrNull()
            // Only for the row still open, and never the category it already has.
            if (currentTxn()?.id == txn.id) {
                editorState.value = editorState.value.copy(
                    suggestion = guess?.suggestion?.takeIf { guess.available && it.category != txn.category },
                    suggestionLoading = false,
                )
            }
        }
    }

    fun acceptSuggestion(suggestion: CategorySuggestion) = edit(TransactionChanges.accept(suggestion))

    /** Picking a category by hand confirms the row and clears a subcategory that no longer fits. */
    fun setCategory(category: String) = edit(
        TransactionChanges(category = Patch.Set(category), subcategory = Patch.Clear, reviewState = Patch.Set("confirmed")),
    )

    fun setNotes(notes: String) {
        val current = currentTxn() ?: return
        if (notes.trim() == current.notes.orEmpty().trim()) return
        edit(TransactionChanges(notes = notes.trim().takeIf { it.isNotEmpty() }?.let { Patch.Set(it) } ?: Patch.Clear))
    }

    fun setMerchant(merchant: String) {
        val current = currentTxn() ?: return
        if (merchant.trim() == current.merchant.orEmpty().trim()) return
        edit(TransactionChanges(merchant = merchant.trim().takeIf { it.isNotEmpty() }?.let { Patch.Set(it) } ?: Patch.Clear))
    }

    fun setReviewState(state: String) = edit(TransactionChanges(reviewState = Patch.Set(state)))

    fun addTag(tag: String) {
        val clean = tag.trim()
        val current = currentTxn() ?: return
        if (clean.isEmpty() || clean in current.tags) return
        save { ledger.addTag(current.id, clean) }
    }

    fun removeTag(tag: String) {
        val current = currentTxn() ?: return
        save { ledger.removeTag(current.id, tag) }
    }

    private fun edit(changes: TransactionChanges) {
        val current = currentTxn() ?: return
        if (changes.isEmpty) return
        save { ledger.editTransaction(current.id, changes) }
    }

    private fun save(call: suspend () -> Txn) {
        if (editorState.value.saving) return
        editorState.value = editorState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            suspendRunCatching { call() }
                .onSuccess { updated ->
                    // The PATCH answers with the row alone; keep the links the detail read had.
                    val previous = (detailState.value as? DetailState.Loaded)?.full
                    val merged = if (previous != null && previous.id == updated.id && updated.transferLegs.isEmpty() && updated.relatedTransactions.isEmpty()) {
                        updated.copy(transferLegs = previous.transferLegs, relatedTransactions = previous.relatedTransactions, provenance = updated.provenance ?: previous.provenance)
                    } else {
                        updated
                    }
                    detailState.value = DetailState.Loaded(merged, merged)
                    edited.value = edited.value + (merged.id to merged)
                    editorState.value = editorState.value.copy(saving = false, suggestion = null)
                }
                .onFailure { error -> editorState.value = editorState.value.copy(saving = false, error = ApiErrors.message(error)) }
        }
    }

    private fun currentTxn(): Txn? = when (val state = detailState.value) {
        is DetailState.Loaded -> state.full
        null -> null
        else -> state.summary
    }
}

private fun Account.toChoice() = AccountChoice(id, displayName)

/** The period a scoped blotter covers, for the line under its title. */
internal fun describe(filter: LedgerFilter): String? = when {
    filter.months.isNotEmpty() -> filter.months.sorted().joinToString(", ") { DisplayDates.shortMonth(it) }
    filter.from != null && filter.to != null && filter.from == filter.to -> DisplayDates.medium(filter.from)
    filter.from != null && filter.to != null -> "${DisplayDates.medium(filter.from)} – ${DisplayDates.medium(filter.to)}"
    filter.from != null -> "Since ${DisplayDates.medium(filter.from)}"
    filter.to != null -> "Until ${DisplayDates.medium(filter.to)}"
    else -> null
}
