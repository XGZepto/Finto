package page.zepto.finto.core.testing

import android.net.Uri
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.AppSettings
import page.zepto.finto.core.data.repository.ImportRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.SettingsRepository
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.data.repository.ThemeMode
import page.zepto.finto.core.model.ImportCapabilities
import page.zepto.finto.core.model.ImportResult
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Card
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.Facets
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.SuggestionResponse
import page.zepto.finto.core.model.ConfirmResponse
import page.zepto.finto.core.model.TransactionChanges
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.NetWorthSeries
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.StatementFreshness
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.model.User

class FakeSessionRepository(user: User = TestData.user) : SessionRepository {
    val mutableState = MutableStateFlow<SessionState>(SessionState.SignedIn(user, "https://demo.finto.zepto.page/"))
    override val state: StateFlow<SessionState> = mutableState
    var signInResult: Result<User> = Result.success(user)
    val signIns = mutableListOf<Triple<String, String, String>>()

    override suspend fun restore() = Unit

    override suspend fun signIn(server: String, identifier: String, password: String): Result<User> {
        signIns += Triple(server, identifier, password)
        return signInResult
    }

    override suspend fun signOut() {
        mutableState.value = SessionState.SignedOut()
    }

    override suspend fun setBaseCurrency(currency: String): Result<User> {
        val current = mutableState.value as SessionState.SignedIn
        val user = current.user.copy(preferences = current.user.preferences.copy(baseCurrency = currency))
        mutableState.value = current.copy(user = user)
        return Result.success(user)
    }
}

/** Answers each summary by its `group_by` and filter through [summaries]. */
class FakeLedgerRepository(
    var summaries: (groupBy: String, filter: LedgerFilter, convertTo: String?) -> SummaryResponse =
        { groupBy, _, _ -> SummaryResponse(groupBy) },
) : LedgerRepository {
    val summaryCalls = mutableListOf<Triple<String, LedgerFilter, String?>>()
    var transactions: List<Txn> = emptyList()
    var accounts: List<Account> = emptyList()
    var positions = PositionsResponse()
    var freshness = StatementFreshness(asOf = "2026-11-05")
    var cards: List<Card> = emptyList()
    var facets = Facets()
    var flows = Flows()
    var netWorthSeries = NetWorthSeries("HKD")
    var compositions: (dimension: String) -> Composition = { Composition(it, "HKD") }
    var coverage = Coverage()
    var integrity = IntegrityReport()
    var installments = InstallmentsResponse()
    var installmentDetails: Map<String, InstallmentPlan> = emptyMap()
    var suggestion = SuggestionResponse()
    var failure: Throwable? = null
    val accountEdits = mutableListOf<Pair<String, String>>()
    val tagEdits = mutableListOf<Triple<String, String, Boolean>>()

    override suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String?, policy: ReadPolicy): SummaryResponse {
        summaryCalls += Triple(groupBy, filter, convertTo)
        return summaries(groupBy, filter, convertTo)
    }

    val edits = mutableListOf<Pair<String, TransactionChanges>>()

    private fun check() = failure?.let { throw it }

    override suspend fun positions(convertTo: String?, policy: ReadPolicy) = positions.also { check() }
    override suspend fun accounts(policy: ReadPolicy) = accounts.also { check() }
    override suspend fun cards(policy: ReadPolicy) = cards.also { check() }
    override suspend fun facets(policy: ReadPolicy) = facets.also { check() }
    override suspend fun flows(convertTo: String, policy: ReadPolicy) = flows.also { check() }
    override suspend fun netWorthSeries(convertTo: String, policy: ReadPolicy) = netWorthSeries.also { check() }
    override suspend fun composition(dimension: String, convertTo: String, policy: ReadPolicy) = compositions(dimension).also { check() }
    override suspend fun coverage(policy: ReadPolicy) = coverage.also { check() }
    override suspend fun integrity(policy: ReadPolicy) = integrity.also { check() }
    override suspend fun installments(activeOnly: Boolean, policy: ReadPolicy) = installments.also { check() }
    override suspend fun installment(id: String) = installmentDetails.getValue(id).also { check() }
    override suspend fun statementFreshness(policy: ReadPolicy) = freshness
    override suspend fun transaction(id: String) = transactions.first { it.id == id }
    override suspend fun categorySuggestion(id: String) = suggestion

    override suspend fun addTag(id: String, tag: String): Txn {
        tagEdits += Triple(id, tag, true)
        return transaction(id)
    }

    override suspend fun removeTag(id: String, tag: String): Txn {
        tagEdits += Triple(id, tag, false)
        return transaction(id)
    }

    private fun account(id: String) = accounts.first { it.id == id }

    override suspend fun closeAccount(id: String, today: String) = account(id).also { accountEdits += id to "close" }
    override suspend fun reopenAccount(id: String) = account(id).also { accountEdits += id to "reopen" }
    override suspend fun watchStatements(id: String, watch: Boolean) =
        account(id).also { accountEdits += id to if (watch) "watch" else "unwatch" }

    val pageCalls = mutableListOf<LedgerFilter>()

    override suspend fun transactionsPage(filter: LedgerFilter, limit: Int, offset: Int, convertTo: String?, policy: ReadPolicy) =
        pageFor(filter).let { rows ->
            pageCalls += filter
            check()
            TxnPage(total = rows.size, limit = limit, offset = offset, items = rows.drop(offset).take(limit))
        }

    /** Rows a page read returns; by default every transaction regardless of the filter. */
    var pageFor: (LedgerFilter) -> List<Txn> = { transactions }

    override suspend fun editTransaction(id: String, changes: TransactionChanges): Txn {
        edits += id to changes
        return transaction(id)
    }

    override fun transactions(filter: LedgerFilter): Flow<PagingData<Txn>> = flowOf(PagingData.from(transactions))
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current() = state.value
    override suspend fun setServer(url: String) { state.value = state.value.copy(serverUrl = url) }
    override suspend fun setThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
    override suspend fun setDynamicColor(enabled: Boolean) { state.value = state.value.copy(dynamicColor = enabled) }
    override suspend fun setLastIdentifier(identifier: String) { state.value = state.value.copy(lastIdentifier = identifier) }
    override suspend fun setCachedUser(json: String?) { state.value = state.value.copy(cachedUserJson = json) }
}

class FakeImportRepository(
    var preview: StagePreview = StagePreview(sha256 = "abc123", filename = "statement.pdf", txnCount = 9),
    var result: ImportResult = ImportResult(status = "imported", txns = 9),
) : ImportRepository {
    var previewError: Throwable? = null
    val confirmed = mutableListOf<String>()
    val discarded = mutableListOf<StagedFile>()

    override suspend fun capabilities() = ImportCapabilities()

    override suspend fun stage(uri: Uri) =
        StagedFile(name = "statement.pdf", sizeBytes = 1024, mimeType = "application/pdf", path = "/tmp/statement.pdf")

    override suspend fun preview(file: StagedFile, review: Boolean): StagePreview {
        previewError?.let { throw it }
        return preview
    }

    override suspend fun confirm(file: StagedFile, sha256: String): ConfirmResponse {
        confirmed += sha256
        return ConfirmResponse(result)
    }

    override suspend fun discard(file: StagedFile) {
        discarded += file
    }
}
