package page.zepto.finto.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.baseCurrency
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Position
import javax.inject.Inject

data class AccountGroup(
    val type: String,
    val positions: List<Position>,
    /** Sum of the converted balances, or null when a rate is missing. */
    val total: Money?,
)

data class AccountsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val currency: String = "HKD",
    val netWorth: Money? = null,
    val unconvertible: List<String> = emptyList(),
    val groups: List<AccountGroup> = emptyList(),
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
            suspendRunCatching { ledger.positions(currency) }
                .onSuccess { response ->
                    mutableState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            netWorth = response.normalised?.netWorth,
                            unconvertible = response.normalised?.unconvertibleCurrencies.orEmpty(),
                            groups = group(response.positions, currency),
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false, refreshing = false, error = ApiErrors.message(error)) }
                }
        }
    }

    private fun group(positions: List<Position>, currency: String): List<AccountGroup> =
        positions
            .filter { it.txnCount > 0 || it.balance.amount != 0L }
            .groupBy { it.accountType }
            .map { (type, rows) ->
                val converted = rows.map { it.balanceConverted }
                AccountGroup(
                    type = type,
                    positions = rows.sortedWith(compareBy({ it.accountName }, { it.currency })),
                    total = if (converted.all { it?.ok == true }) Money(converted.sumOf { it!!.amount }, currency) else null,
                )
            }
            .sortedBy { TYPE_ORDER.indexOf(it.type).let { index -> if (index < 0) TYPE_ORDER.size else index } }

    private companion object {
        val TYPE_ORDER = listOf("checking", "current", "savings", "multi_currency", "credit_card", "investment", "mpf")
    }
}
