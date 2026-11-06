package page.zepto.finto.feature.accounts

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
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.NetWorthPoint
import page.zepto.finto.core.model.NetWorthSeries
import javax.inject.Inject

/** Net worth on the last day every statement covers, and who it is waiting on. */
data class SettledWorth(val asOf: String, val worth: Money, val limitedBy: List<String>)

data class AccountsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = "HKD",
    val netWorth: Money? = null,
    val settled: SettledWorth? = null,
    val assets: Money? = null,
    val liabilities: Money? = null,
    val series: List<NetWorthPoint> = emptyList(),
    val unconvertible: List<String> = emptyList(),
    val groups: List<ProductGroup> = emptyList(),
    val flows: List<AccountFlowRow> = emptyList(),
    val transfers: List<InternalTransfer> = emptyList(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = mutableState.asStateFlow()
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

    private fun load(refreshing: Boolean = false) {
        job?.cancel()
        mutableState.update { it.copy(loading = !refreshing && it.groups.isEmpty(), refreshing = refreshing, error = null) }
        job = viewModelScope.launch {
            val currency = mutableState.value.currency
            staleWhileRevalidate(refreshing) { policy -> fetch(currency, policy) }
                .catch { error -> mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) } }
                .collect { loaded -> mutableState.value = loaded.copy(refreshing = refreshing) }
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(currency: String, policy: ReadPolicy): AccountsUiState = coroutineScope {
        val positions = async { ledger.positions(currency, policy) }
        val accounts = async { ledger.accounts(policy) }
        val cards = async { suspendRunCatching { ledger.cards(policy) }.getOrDefault(emptyList()) }
        // The chart and the flows are extras: the page stands without them.
        val flows = async { suspendRunCatching { ledger.flows(currency, policy) }.getOrDefault(Flows()) }
        val series = async { suspendRunCatching { ledger.netWorthSeries(currency, policy) }.getOrDefault(NetWorthSeries(currency)) }

        val response = positions.await()
        val accountList = accounts.await()
        val names = accountList.associate { it.id to it.displayName }
        val settled = response.complete?.let { complete ->
            SettledWorth(complete.asOf, complete.netWorth, complete.limitedBy.map { names[it] ?: it })
        }
        val byType = response.complete?.byType ?: response.normalised?.byType.orEmpty()
        val worth = settled?.worth ?: response.normalised?.netWorth
        AccountsUiState(
            loading = false,
            currency = currency,
            netWorth = response.normalised?.netWorth,
            settled = settled,
            assets = worth?.let { AccountsLogic.assets(byType, it.currency) },
            liabilities = worth?.let { AccountsLogic.liabilities(byType, it.currency) },
            series = series.await().points,
            unconvertible = response.normalised?.unconvertibleCurrencies.orEmpty(),
            groups = AccountsLogic.groups(AccountsLogic.rows(accountList, response.positions, cards.await())),
            flows = AccountsLogic.flowRows(flows.await(), names, currency),
            transfers = AccountsLogic.internalTransfers(flows.await(), names),
        )
    }
}
