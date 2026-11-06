package page.zepto.finto.core.data.paging

import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.paging.PagingConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import java.io.IOException

class TransactionsPagingSourceTest {
    private val ledger = (1..120).map { index ->
        Txn(
            id = "t$index", date = "2026-11-01", accountId = "a", accountName = "A", accountCurrency = "HKD",
            institutionId = "i", description = "Row $index", booked = Money(-index.toLong(), "HKD"), kind = "purchase",
        )
    }
    private val offsets = mutableListOf<Int>()
    private val source = TransactionsPagingSource(LedgerFilter(spendingOnly = true)) { filter, limit, offset ->
        assertTrue(filter.spendingOnly)
        offsets += offset
        TxnPage(total = ledger.size, limit = limit, offset = offset, items = ledger.drop(offset).take(limit))
    }

    @Test
    fun pagesByOffsetUntilTheTotal() = runTest {
        val pager = TestPager(PagingConfig(pageSize = 50, initialLoadSize = 50), source)
        val first = pager.refresh() as PagingSource.LoadResult.Page
        assertEquals(50, first.data.size)
        assertEquals(50, first.nextKey)
        val second = pager.append() as PagingSource.LoadResult.Page
        assertEquals("t51", second.data.first().id)
        val last = pager.append() as PagingSource.LoadResult.Page
        assertEquals(20, last.data.size)
        assertNull(last.nextKey)
        assertEquals(listOf(0, 50, 100), offsets)
    }

    @Test
    fun failuresBecomeErrorResults() = runTest {
        val failing = TransactionsPagingSource(LedgerFilter()) { _, _, _ -> throw IOException("offline") }
        val result = TestPager(PagingConfig(pageSize = 50), failing).refresh()
        assertTrue(result is PagingSource.LoadResult.Error)
    }
}
