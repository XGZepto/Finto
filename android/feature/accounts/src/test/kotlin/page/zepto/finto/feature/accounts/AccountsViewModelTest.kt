package page.zepto.finto.feature.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.ConvertedMoney
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Position
import page.zepto.finto.core.model.PositionsNormalised
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule

class AccountsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun position(id: String, type: String, amount: Long, currency: String = "HKD", converted: Long? = amount) = Position(
        accountId = id, accountName = id.uppercase(), institutionId = "bank", accountType = type, currency = currency,
        txnCount = 5, balance = Money(amount, currency),
        balanceConverted = converted?.let { ConvertedMoney(it, "HKD", ok = true) } ?: ConvertedMoney(0, "HKD", ok = false),
    )

    @Test
    fun groupsByTypeInAStableOrderWithConvertedTotals() {
        val ledger = FakeLedgerRepository().apply {
            positions = PositionsResponse(
                positions = listOf(
                    position("amex", "credit_card", -50_000),
                    position("hsbc", "checking", 200_000),
                    position("wise", "multi_currency", 10_000, currency = "USD", converted = 78_000),
                    position("gold", "multi_currency", 1, currency = "XAU", converted = null),
                ),
                normalised = PositionsNormalised(to = "HKD", netWorth = Money(228_000, "HKD"), unconvertibleCurrencies = listOf("XAU")),
            )
        }
        val state = AccountsViewModel(ledger, FakeSessionRepository()).state.value
        assertEquals(listOf("checking", "multi_currency", "credit_card"), state.groups.map { it.type })
        assertEquals(Money(200_000, "HKD"), state.groups[0].total)
        assertNull("a missing rate leaves the group total unstated", state.groups[1].total)
        assertEquals(Money(228_000, "HKD"), state.netWorth)
        assertEquals(listOf("XAU"), state.unconvertible)
    }
}
