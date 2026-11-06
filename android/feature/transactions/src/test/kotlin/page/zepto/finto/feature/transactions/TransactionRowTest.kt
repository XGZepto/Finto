package page.zepto.finto.feature.transactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShoppingBag
import org.junit.Assert.assertEquals
import org.junit.Test
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.testing.TestData

class TransactionRowTest {
    @Test
    fun iconsFollowTheKindOfMovement() {
        assertEquals(Icons.Outlined.ShoppingBag, kindIcon(TestData.txn("1", "2026-11-01", "Gift", -4_500, category = null)))
        assertEquals(Icons.Outlined.Restaurant, kindIcon(TestData.txn("1b", "2026-11-01", "Coffee", -4_500, category = "dining")))
        assertEquals(Icons.Outlined.CreditCard, kindIcon(TestData.txn("2", "2026-11-01", "Payment", 100_000, category = null, kind = "cc_payment")))
        assertEquals(Icons.Outlined.Savings, kindIcon(TestData.txn("3", "2026-11-01", "Interest", 300, category = null, kind = "interest")))
        val transfer = TestData.txn("4", "2026-11-01", "To savings", -10_000).copy(transferGroupId = "g")
        assertEquals(Icons.AutoMirrored.Outlined.CompareArrows, kindIcon(transfer))
    }

    @Test
    fun destinationKnowsWhenItIsScoped() {
        assertEquals(false, ActivityDestination().isScoped)
        val scoped = ActivityDestination.of(LedgerFilter(categories = listOf("dining"), from = "2026-01-01"), "Dining")
        assertEquals(true, scoped.isScoped)
        assertEquals(listOf("dining"), scoped.scope.categories)
        assertEquals("Since ${DisplayDates.medium("2026-01-01")}", describe(scoped.scope))
        assertEquals(
            "${DisplayDates.medium("2026-02-01")} – ${DisplayDates.medium("2026-02-28")}",
            describe(LedgerFilter(from = "2026-02-01", to = "2026-02-28")),
        )
    }
}
