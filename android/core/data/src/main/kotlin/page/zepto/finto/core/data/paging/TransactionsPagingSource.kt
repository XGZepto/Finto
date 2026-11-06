package page.zepto.finto.core.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage

/** Offset pages of the blotter, newest first. */
internal class TransactionsPagingSource(
    private val filter: LedgerFilter,
    private val fetch: suspend (filter: LedgerFilter, limit: Int, offset: Int) -> TxnPage,
) : PagingSource<Int, Txn>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Txn> {
        val offset = params.key ?: 0
        return try {
            val page = fetch(filter, params.loadSize, offset)
            val loaded = offset + page.items.size
            val more = page.total?.let { loaded < it } ?: (page.items.size >= params.loadSize)
            LoadResult.Page(
                data = page.items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (more && page.items.isNotEmpty()) loaded else null,
            )
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Txn>): Int? = null
}
