package page.zepto.finto.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import page.zepto.finto.core.data.paging.TransactionsPagingSource
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.StatementFreshness
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.network.FintoApi
import javax.inject.Inject
import javax.inject.Singleton

interface LedgerRepository {
    suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String? = null): SummaryResponse
    suspend fun positions(convertTo: String?): PositionsResponse
    suspend fun accounts(): List<Account>
    suspend fun statementFreshness(): StatementFreshness
    suspend fun transaction(id: String): Txn
    suspend fun transactionsPage(filter: LedgerFilter, limit: Int, offset: Int = 0): TxnPage
    fun transactions(filter: LedgerFilter): Flow<PagingData<Txn>>
}

@Singleton
internal class NetworkLedgerRepository @Inject constructor(
    private val api: FintoApi,
) : LedgerRepository {

    override suspend fun summary(groupBy: String, filter: LedgerFilter, convertTo: String?): SummaryResponse =
        api.summary(
            groupBy = groupBy,
            from = filter.from,
            to = filter.to,
            months = filter.months.ifEmpty { null },
            accounts = filter.accounts.ifEmpty { null },
            categories = filter.categories.ifEmpty { null },
            query = filter.query?.takeIf { it.isNotBlank() },
            spendingOnly = filter.spendingOnly.takeIf { it },
            includeTransfers = filter.includeTransfers.takeIf { it },
            maxAmount = filter.maxAmount,
            convertTo = convertTo,
        )

    override suspend fun positions(convertTo: String?): PositionsResponse = api.positions(convertTo)

    override suspend fun accounts(): List<Account> = api.accounts().accounts

    override suspend fun statementFreshness(): StatementFreshness = api.statementFreshness()

    override suspend fun transaction(id: String): Txn = api.transaction(id)

    override suspend fun transactionsPage(filter: LedgerFilter, limit: Int, offset: Int): TxnPage =
        api.transactions(
            from = filter.from,
            to = filter.to,
            months = filter.months.ifEmpty { null },
            accounts = filter.accounts.ifEmpty { null },
            categories = filter.categories.ifEmpty { null },
            query = filter.query?.takeIf { it.isNotBlank() },
            spendingOnly = filter.spendingOnly.takeIf { it },
            includeTransfers = filter.includeTransfers.takeIf { it },
            maxAmount = filter.maxAmount,
            limit = limit,
            offset = offset,
        )

    override fun transactions(filter: LedgerFilter): Flow<PagingData<Txn>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { TransactionsPagingSource(filter, ::transactionsPage) },
    ).flow

    private companion object {
        const val PAGE_SIZE = 50
    }
}
