package page.zepto.finto.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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

    private val route: ActivityDestination = savedStateHandle.toRoute()
    private val scope: LedgerFilter = route.scope

    private val query = MutableStateFlow("")
    private val spendingOnly = MutableStateFlow(scope.spendingOnly)
    private val account = MutableStateFlow<String?>(null)
    private val accounts = MutableStateFlow<List<AccountChoice>>(emptyList())
    private val detailState = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = detailState.asStateFlow()

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
