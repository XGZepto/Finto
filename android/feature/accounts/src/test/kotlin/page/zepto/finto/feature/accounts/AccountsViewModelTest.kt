package page.zepto.finto.feature.accounts

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Card
import page.zepto.finto.core.model.ConvertedMoney
import page.zepto.finto.core.model.ExternalNode
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.InternalFlow
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.NormalisedFlows
import page.zepto.finto.core.model.Position
import page.zepto.finto.core.model.PositionsNormalised
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.SettledPositions
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.TypeBalance
import page.zepto.finto.core.testing.FakeLedgerRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import page.zepto.finto.core.testing.TestData

private fun account(id: String, type: String = "checking", group: String? = null, institution: String = "hsbc", closed: String? = null, watch: Boolean = true) =
    Account(id, institution, id.replace('_', ' ').uppercase(), type, "HKD", balanceGroup = group, closedOn = closed, watchStatements = watch)

private fun position(id: String, amount: Long, currency: String = "HKD", type: String = "checking") = Position(
    accountId = id, accountName = id, institutionId = "hsbc", accountType = type, currency = currency, txnCount = 5,
    balance = Money(amount, currency), balanceConverted = ConvertedMoney(amount, "HKD", ok = true),
)

class AccountsLogicTest {
    private val accounts = listOf(
        account("one_hkd", group = "hsbc_one"),
        account("one_usd", group = "hsbc_one"),
        account("amex", type = "credit_card", institution = "amex"),
    )
    private val positions = listOf(position("one_hkd", 100_00), position("one_usd", 50_00, "USD"), position("one_usd", 1_00, "HKD"), position("amex", -20_00, type = "credit_card"))

    @Test
    fun productsGroupSubaccountsAndTotalPerCurrency() {
        val groups = AccountsLogic.groups(AccountsLogic.rows(accounts, positions, emptyList()))
        assertEquals(listOf("account:amex", "hsbc_one"), groups.map { it.key })
        val one = groups[1]
        assertEquals(page.zepto.finto.core.model.Labels.humanize("hsbc_one"), one.name)
        assertEquals(listOf(Money(101_00, "HKD"), Money(50_00, "USD")), one.totals)
        assertTrue(groups[0].single)
    }

    @Test
    fun siblingsShareABalanceGroup() {
        val rows = AccountsLogic.rows(accounts, positions, emptyList())
        assertEquals(listOf("one_hkd", "one_usd"), AccountsLogic.siblings(rows, "one_usd").map { it.account.id })
        assertEquals(listOf("amex"), AccountsLogic.siblings(rows, "amex").map { it.account.id })
    }

    @Test
    fun lineagesFollowReissuesAndSupplementaryHolders() {
        val cards = listOf(
            Card("c1", "amex", last4 = "1001"),
            Card("c2", "amex", last4 = "1002", replacesCardId = "c1", lineageRoot = "c1"),
            Card("c3", "amex", cardholderName = "Sam", last4 = "2001", isSupplementary = true),
        )
        val lineages = AccountsLogic.lineages(cards, listOf("amex"))
        assertEquals(CardLineage("You", false, listOf("1001", "1002"), reissued = true), lineages[0])
        assertEquals(CardLineage("Sam", true, listOf("2001"), reissued = false), lineages[1])
    }

    @Test
    fun accountFlowTakesCategoriesAndOtherAccountsLargestFirst() {
        val flows = Flows(
            normalised = NormalisedFlows(
                currency = "HKD",
                externalNodes = listOf(
                    ExternalNode("amex", "dining", 10, Money(0, "HKD"), Money(-500_00, "HKD")),
                    ExternalNode("amex", "refunds", 1, Money(20_00, "HKD"), Money(0, "HKD")),
                    ExternalNode("other", "travel", 1, Money(0, "HKD"), Money(-900_00, "HKD")),
                ),
                internal = listOf(InternalFlow("one_hkd", "amex", 2, Money(800_00, "HKD"))),
            ),
        )
        val flow = AccountsLogic.accountFlow(flows, "amex", mapOf("one_hkd" to "HSBC One"))
        assertEquals(listOf("HSBC One", "Refunds"), flow.incoming.map { it.label })
        assertEquals("one_hkd", flow.incoming[0].toAccount)
        assertEquals(listOf("Dining"), flow.outgoing.map { it.label })
        assertEquals(500_00, flow.outgoing[0].amount)
    }

    @Test
    fun assetsAndLiabilitiesSplitByType() {
        val byType = listOf(TypeBalance("checking", Money(300, "HKD")), TypeBalance("credit_card", Money(-100, "HKD")))
        assertEquals(Money(300, "HKD"), AccountsLogic.assets(byType, "HKD"))
        assertEquals(Money(-100, "HKD"), AccountsLogic.liabilities(byType, "HKD"))
    }
}

class AccountsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun settledNetWorthLeadsAndNamesWhatItWaitsOn() {
        val ledger = FakeLedgerRepository().apply {
            accounts = listOf(account("one_hkd"), account("amex", type = "credit_card"))
            positions = PositionsResponse(
                positions = listOf(position("one_hkd", 1_000_00), position("amex", -200_00, type = "credit_card")),
                normalised = PositionsNormalised("HKD", Money(800_00, "HKD"), unconvertibleCurrencies = listOf("XAU")),
                complete = SettledPositions(
                    asOf = "2026-10-31",
                    limitedBy = listOf("amex"),
                    netWorth = Money(700_00, "HKD"),
                    byType = listOf(TypeBalance("checking", Money(900_00, "HKD")), TypeBalance("credit_card", Money(-200_00, "HKD"))),
                ),
            )
        }
        val state = AccountsViewModel(ledger, FakeSessionRepository()).state.value
        assertFalse(state.loading)
        assertEquals(SettledWorth("2026-10-31", Money(700_00, "HKD"), listOf("AMEX")), state.settled)
        assertEquals(Money(800_00, "HKD"), state.netWorth)
        assertEquals(Money(900_00, "HKD"), state.assets)
        assertEquals(Money(-200_00, "HKD"), state.liabilities)
        assertEquals(listOf("XAU"), state.unconvertible)
        assertEquals(2, state.groups.size)
    }

    @Test
    fun failureWithNothingCachedIsAnError() {
        val ledger = FakeLedgerRepository().apply { failure = IllegalStateException("offline") }
        val state = AccountsViewModel(ledger, FakeSessionRepository()).state.value
        assertNotNull(state.error)
        assertTrue(state.groups.isEmpty())
    }
}

class AccountDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ledger = FakeLedgerRepository().apply {
        accounts = listOf(account("one_hkd", group = "hsbc_one"), account("one_usd", group = "hsbc_one"), account("amex", type = "credit_card"))
        positions = PositionsResponse(positions = listOf(position("one_hkd", 1_000_00), position("one_usd", 10_00, "USD")))
        transactions = listOf(TestData.txn("t1", "2026-11-01", "Coffee", -45_00))
        summaries = { groupBy, filter, convertTo ->
            assertNull("account pages use native currencies", convertTo)
            when (groupBy) {
                "kind" -> SummaryResponse(groupBy, rows = listOf(TestData.row("purchase", 300_00, count = 7)))
                "category" -> SummaryResponse(groupBy, rows = listOf(TestData.row("dining", 200_00), TestData.row("groceries", 100_00)))
                "cardholder" -> SummaryResponse(groupBy, rows = listOf(TestData.row("(none)", 50_00), TestData.row("Sam", 250_00)))
                else -> SummaryResponse(groupBy, rows = listOf(TestData.row("2026-10", 300_00)))
            }
        }
    }

    private fun viewModel(id: String, group: Boolean = false) =
        AccountDetailViewModel(SavedStateHandle(mapOf("id" to id, "group" to group)), ledger, FakeSessionRepository())

    @Test
    fun anAccountPageScopesEveryFigureToTheAccount() {
        val state = viewModel("one_hkd").state.value
        assertEquals("ONE HKD", state.title)
        assertEquals(listOf("one_hkd"), state.scope)
        assertEquals(7, state.transactionCount)
        assertEquals(1, state.cardholders)
        assertEquals("dining", state.topCategories.first().bucket)
        assertEquals(listOf("one_hkd", "one_usd"), state.siblings.map { it.account.id })
        assertEquals("hsbc_one", state.parent?.key)
        assertEquals(AccountStatus.Open, state.status)
        assertTrue(ledger.summaryCalls.all { it.second.accounts == listOf("one_hkd") })
    }

    @Test
    fun aProductPageCoversAllItsSubaccounts() {
        val state = viewModel("hsbc_one", group = true).state.value
        assertEquals(page.zepto.finto.core.model.Labels.humanize("hsbc_one"), state.title)
        assertEquals(listOf("one_hkd", "one_usd"), state.scope)
        assertNull(state.status)
    }

    @Test
    fun anUnknownAccountSaysSo() {
        assertTrue(viewModel("nope").state.value.notFound)
    }

    @Test
    fun statusActionsPatchTheAccount() {
        val vm = viewModel("one_hkd")
        vm.watchStatements(false)
        vm.close()
        assertEquals(listOf("one_hkd" to "unwatch", "one_hkd" to "close"), ledger.accountEdits)
    }
}
