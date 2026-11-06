package page.zepto.finto.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import page.zepto.finto.core.data.staleWhileRevalidate
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.Txn
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Whether statements are expected for an account, and the actions that change it. */
enum class AccountStatus { Open, NotWatching, Closed }

data class AccountDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notFound: Boolean = false,
    val currency: String = "HKD",
    val title: String = "",
    val subtitle: String = "",
    /** The account ids this page is about: one, or every subaccount of a product. */
    val scope: List<String> = emptyList(),
    val account: Account? = null,
    val row: AccountRow? = null,
    val group: ProductGroup? = null,
    /** The product a subaccount belongs to, when it has siblings. */
    val parent: ProductGroup? = null,
    val siblings: List<AccountRow> = emptyList(),
    val transactionCount: Int = 0,
    val cardholders: Int = 0,
    val topCategories: List<SummaryRow> = emptyList(),
    val months: List<SummaryRow> = emptyList(),
    val byHolder: List<SummaryRow> = emptyList(),
    val byKind: List<SummaryRow> = emptyList(),
    val lineages: List<CardLineage> = emptyList(),
    val flow: AccountFlow = AccountFlow(emptyList(), emptyList()),
    val flowCurrency: String = "HKD",
    val transfers: List<InternalTransfer> = emptyList(),
    val recent: List<Txn> = emptyList(),
    val statusBusy: Boolean = false,
) {
    val status: AccountStatus?
        get() = account?.let {
            when {
                it.closedOn != null -> AccountStatus.Closed
                !it.watchStatements -> AccountStatus.NotWatching
                else -> AccountStatus.Open
            }
        }

    val hasActivity: Boolean get() = transactionCount > 0 || recent.isNotEmpty()

    val scopeFilter: LedgerFilter get() = LedgerFilter(accounts = scope)
}

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: LedgerRepository,
    session: SessionRepository,
) : ViewModel() {

    // Type-safe navigation stores each route property under its own name; reading
    // them directly keeps the view model testable without an Android Bundle.
    private val route = AccountDetailDestination(
        id = checkNotNull(savedStateHandle.get<String>("id")),
        group = savedStateHandle.get<Boolean>("group") ?: false,
    )
    private val clock: Clock = Clock.systemDefaultZone()
    private val mutableState = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.SignedIn>()
                .map { it.user.baseCurrency }
                .distinctUntilChanged()
                .collect { currency ->
                    mutableState.update { it.copy(currency = currency) }
                    load()
                }
        }
    }

    fun refresh() = load(refreshing = true)

    fun close() = changeStatus { id -> ledger.closeAccount(id, LocalDate.now(clock).toString()) }

    fun reopen() = changeStatus { id -> ledger.reopenAccount(id) }

    fun watchStatements(watch: Boolean) = changeStatus { id -> ledger.watchStatements(id, watch) }

    private fun changeStatus(action: suspend (String) -> Account) {
        val account = mutableState.value.account ?: return
        if (mutableState.value.statusBusy) return
        mutableState.update { it.copy(statusBusy = true, error = null) }
        viewModelScope.launch {
            suspendRunCatching { action(account.id) }
                .onSuccess { updated ->
                    mutableState.update { it.copy(statusBusy = false, account = updated) }
                    load(refreshing = false, quiet = true)
                }
                .onFailure { error -> mutableState.update { it.copy(statusBusy = false, error = ApiErrors.message(error)) } }
        }
    }

    private fun load(refreshing: Boolean = false, quiet: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refreshing && !quiet && it.title.isEmpty(), refreshing = refreshing, error = if (quiet) it.error else null) }
        job = viewModelScope.launch {
            val currency = mutableState.value.currency
            staleWhileRevalidate(refreshing || quiet) { policy -> fetch(currency, policy) }
                .catch { error -> mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) } }
                .collect { loaded -> mutableState.update { loaded.copy(refreshing = refreshing, statusBusy = it.statusBusy) } }
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(currency: String, policy: ReadPolicy): AccountDetailUiState = coroutineScope {
        val accountsCall = async { ledger.accounts(policy) }
        val cardsCall = async { suspendRunCatching { ledger.cards(policy) }.getOrDefault(emptyList()) }
        val positionsCall = async { ledger.positions(currency, policy) }
        val flowsCall = async { suspendRunCatching { ledger.flows(currency, policy) }.getOrDefault(Flows()) }

        val accounts = accountsCall.await()
        val cards = cardsCall.await()
        val rows = AccountsLogic.rows(accounts, positionsCall.await().positions, cards)
        val groups = AccountsLogic.groups(rows)
        val names = accounts.associate { it.id to it.displayName }

        val group = if (route.group) groups.firstOrNull { it.key == route.id } else null
        val row = if (route.group) null else rows.firstOrNull { it.account.id == route.id }
        if (group == null && row == null) {
            return@coroutineScope AccountDetailUiState(loading = false, notFound = true, currency = currency, title = "Account")
        }
        val scope = group?.children?.map { it.account.id } ?: listOf(route.id)
        val filter = LedgerFilter(accounts = scope)

        // Native currencies, as the web shows them: an account's own amounts need no rate.
        suspend fun split(dimension: String): List<SummaryRow> =
            suspendRunCatching { ledger.summary(dimension, filter, policy = policy) }.getOrDefault(SummaryResponse(dimension)).rows
        val kinds = async { split("kind") }
        val categories = async { split("category") }
        val holders = async { split("cardholder") }
        val months = async { split("month") }
        val recent = async {
            suspendRunCatching { ledger.transactionsPage(filter, limit = 12, policy = policy).items }.getOrDefault(emptyList())
        }

        val siblings = row?.let { AccountsLogic.siblings(rows, it.account.id) }.orEmpty()
        val parent = row?.account?.balanceGroup?.takeIf { siblings.size > 1 }?.let { key -> groups.firstOrNull { it.key == key } }
        val flows = flowsCall.await()
        val byKind = kinds.await().sortedByDescending { it.txnCount }
        val byHolder = holders.await().filter { it.spend.amount != 0L }.sortedByDescending { it.spend.amount }
        AccountDetailUiState(
            loading = false,
            currency = currency,
            title = group?.name ?: row!!.account.displayName,
            subtitle = group?.let {
                listOfNotNull(
                    Labels.humanize(it.institution),
                    "${it.children.size} subaccounts",
                    it.cards.takeIf { count -> count > 0 }?.let { count -> if (count == 1) "1 card" else "$count cards" },
                ).joinToString(" · ")
            } ?: AccountsLogic.subtitle(row!!.account),
            scope = scope,
            account = row?.account,
            row = row,
            group = group,
            parent = parent,
            siblings = siblings.takeIf { it.size > 1 }.orEmpty(),
            transactionCount = byKind.sumOf { it.txnCount },
            cardholders = byHolder.count { it.bucket.isNotBlank() && it.bucket != "(none)" },
            topCategories = categories.await().filter { it.spend.amount != 0L }.sortedByDescending { it.spend.amount }.take(7),
            months = months.await().filter { it.spend.amount != 0L }.sortedWith(compareBy({ it.bucket }, { it.currency })),
            byHolder = byHolder,
            byKind = byKind,
            lineages = AccountsLogic.lineages(cards, scope),
            flow = row?.let { AccountsLogic.accountFlow(flows, it.account.id, names) } ?: AccountFlow(emptyList(), emptyList()),
            flowCurrency = flows.normalised?.currency ?: currency,
            transfers = AccountsLogic.internalTransfers(flows, names).filter { it.from in scope || it.to in scope },
            recent = recent.await(),
        )
    }
}

/** The largest monthly outflow in the list, for scaling its bars. */
internal fun List<SummaryRow>.peakSpend(): Long = maxOfOrNull { it.spend.amount }?.coerceAtLeast(1L) ?: 1L

internal fun Money.isZero() = amount == 0L
