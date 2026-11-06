package page.zepto.finto.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.paging.TransactionsPagingSource
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.AccountChanges
import page.zepto.finto.core.model.Card
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.Facets
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.StatementFreshness
import page.zepto.finto.core.model.SuggestionResponse
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.TagRequest
import page.zepto.finto.core.model.TransactionChanges
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.network.FintoApi
import page.zepto.finto.core.network.NetworkCache
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("TooManyFunctions")
interface LedgerRepository {
    suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String? = null, policy: ReadPolicy = ReadPolicy.Default): SummaryResponse
    suspend fun positions(convertTo: String?, policy: ReadPolicy = ReadPolicy.Default): PositionsResponse
    suspend fun accounts(policy: ReadPolicy = ReadPolicy.Default): List<Account>
    suspend fun cards(policy: ReadPolicy = ReadPolicy.Default): List<Card> = emptyList()
    suspend fun facets(policy: ReadPolicy = ReadPolicy.Default): Facets = Facets()
    suspend fun flows(convertTo: String, policy: ReadPolicy = ReadPolicy.Default): Flows = Flows()
    suspend fun composition(dimension: String, convertTo: String, policy: ReadPolicy = ReadPolicy.Default): Composition =
        Composition(dimension, convertTo)
    suspend fun coverage(policy: ReadPolicy = ReadPolicy.Default): Coverage = Coverage()
    suspend fun statementFreshness(policy: ReadPolicy = ReadPolicy.Default): StatementFreshness
    suspend fun integrity(policy: ReadPolicy = ReadPolicy.Default): IntegrityReport = IntegrityReport()
    suspend fun installments(activeOnly: Boolean, policy: ReadPolicy = ReadPolicy.Default): InstallmentsResponse = InstallmentsResponse()
    suspend fun installment(id: String): InstallmentPlan = error("no plan $id")
    suspend fun transaction(id: String): Txn
    suspend fun transactionsPage(
        filter: LedgerFilter,
        limit: Int,
        offset: Int = 0,
        convertTo: String? = null,
        policy: ReadPolicy = ReadPolicy.Default,
    ): TxnPage
    fun transactions(filter: LedgerFilter): Flow<PagingData<Txn>>

    suspend fun editTransaction(id: String, changes: TransactionChanges): Txn = error("read-only")
    suspend fun addTag(id: String, tag: String): Txn = error("read-only")
    suspend fun removeTag(id: String, tag: String): Txn = error("read-only")
    suspend fun categorySuggestion(id: String): SuggestionResponse = SuggestionResponse()
    suspend fun closeAccount(id: String, today: String): Account = error("read-only")
    suspend fun reopenAccount(id: String): Account = error("read-only")
    suspend fun watchStatements(id: String, watch: Boolean): Account = error("read-only")
}

@Singleton
internal class NetworkLedgerRepository @Inject constructor(
    private val api: FintoApi,
    private val cache: NetworkCache,
) : LedgerRepository {

    override suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String?, policy: ReadPolicy): SummaryResponse =
        api.summary(
            groupBy = groupBy,
            scalars = filter.scalarParams(),
            months = filter.months.orNull(),
            accounts = filter.accounts.orNull(),
            cards = filter.cards.orNull(),
            cardholders = filter.cardholders.orNull(),
            institutions = filter.institutions.orNull(),
            categories = filter.categories.orNull(),
            kinds = filter.kinds.orNull(),
            tags = filter.tags.orNull(),
            detail = filter.detail.orNull(),
            statements = filter.statements.orNull(),
            convertTo = convertTo,
            cacheControl = policy.header,
        )

    override suspend fun positions(convertTo: String?, policy: ReadPolicy) = api.positions(convertTo, policy.header)
    override suspend fun accounts(policy: ReadPolicy) = api.accounts(policy.header).accounts
    override suspend fun cards(policy: ReadPolicy) = api.cards(policy.header).cards
    override suspend fun facets(policy: ReadPolicy) = api.facets(policy.header)
    override suspend fun flows(convertTo: String, policy: ReadPolicy) = api.flows(convertTo, policy.header)
    override suspend fun composition(dimension: String, convertTo: String, policy: ReadPolicy) =
        api.composition(dimension, convertTo, cacheControl = policy.header)
    override suspend fun coverage(policy: ReadPolicy) = api.coverage(policy.header)
    override suspend fun statementFreshness(policy: ReadPolicy) = api.statementFreshness(policy.header)
    override suspend fun integrity(policy: ReadPolicy) = api.integrity(policy.header)
    override suspend fun installments(activeOnly: Boolean, policy: ReadPolicy) = api.installments(activeOnly, policy.header)
    override suspend fun installment(id: String) = api.installment(id)
    override suspend fun transaction(id: String): Txn = api.transaction(id)

    override suspend fun transactionsPage(
        filter: LedgerFilter,
        limit: Int,
        offset: Int,
        convertTo: String?,
        policy: ReadPolicy,
    ): TxnPage = api.transactions(
        scalars = filter.scalarParams(),
        months = filter.months.orNull(),
        accounts = filter.accounts.orNull(),
        cards = filter.cards.orNull(),
        cardholders = filter.cardholders.orNull(),
        institutions = filter.institutions.orNull(),
        categories = filter.categories.orNull(),
        kinds = filter.kinds.orNull(),
        tags = filter.tags.orNull(),
        detail = filter.detail.orNull(),
        statements = filter.statements.orNull(),
        limit = limit,
        offset = offset,
        convertTo = convertTo,
        cacheControl = policy.header,
    )

    override fun transactions(filter: LedgerFilter): Flow<PagingData<Txn>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false),
        pagingSourceFactory = { TransactionsPagingSource(filter) { f, limit, offset -> transactionsPage(f, limit, offset) } },
    ).flow

    override suspend fun editTransaction(id: String, changes: TransactionChanges): Txn =
        api.patchTransaction(id, changes.toJson()).also { cache.clear() }

    override suspend fun addTag(id: String, tag: String): Txn =
        api.addTag(id, TagRequest(tag.trim())).also { cache.clear() }

    override suspend fun removeTag(id: String, tag: String): Txn =
        api.removeTag(id, tag).also { cache.clear() }

    override suspend fun categorySuggestion(id: String) = api.categorySuggestion(id)

    override suspend fun closeAccount(id: String, today: String) =
        api.patchAccount(id, AccountChanges.close(today)).also { cache.clear() }

    override suspend fun reopenAccount(id: String) =
        api.patchAccount(id, AccountChanges.reopen()).also { cache.clear() }

    override suspend fun watchStatements(id: String, watch: Boolean) =
        api.patchAccount(id, AccountChanges.watchStatements(watch)).also { cache.clear() }

    private fun List<String>.orNull(): List<String>? = ifEmpty { null }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
