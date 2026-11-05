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
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.LedgerFilter
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

    override suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String?): SummaryResponse {
        summaryCalls += Triple(groupBy, filter, convertTo)
        return summaries(groupBy, filter, convertTo)
    }

    override suspend fun positions(convertTo: String?) = positions
    override suspend fun accounts() = accounts
    override suspend fun statementFreshness() = freshness
    override suspend fun transaction(id: String) = transactions.first { it.id == id }

    override suspend fun transactionsPage(filter: LedgerFilter, limit: Int, offset: Int) =
        TxnPage(total = transactions.size, limit = limit, offset = offset, items = transactions.drop(offset).take(limit))

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

    override suspend fun confirm(file: StagedFile, sha256: String): ImportResult {
        confirmed += sha256
        return result
    }

    override suspend fun discard(file: StagedFile) {
        discarded += file
    }
}
