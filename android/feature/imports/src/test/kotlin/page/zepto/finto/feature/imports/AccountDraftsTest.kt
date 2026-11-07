package page.zepto.finto.feature.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.InOut
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.model.StatementCard

class AccountDraftsTest {
    private val card = AccountDraft("HSBC Red", "hsbc_hk", "credit_card", "HKD", "4242", "ALEX SAMPLE")

    @Test
    fun anIdAlreadyInTheLedgerGetsTheNextSuffix() {
        val existing = listOf(Account("hsbc_hk_card_4242", "hsbc_hk", "Old", "credit_card", "HKD"))
        assertEquals("hsbc_hk_card_4242_2", AccountDrafts.toNewAccount(card, existing).id)
    }

    @Test
    fun aBankAccountCarriesNoCard() {
        val bank = card.copy(accountType = "savings", displayName = "HSBC Savings")
        val request = AccountDrafts.toNewAccount(bank, emptyList())
        assertEquals("hsbc_hk_savings", request.id)
        assertEquals(null, request.last4)
        assertEquals(null, request.cardholderName)
    }

    @Test
    fun idsAreSlugsTheServerAccepts() {
        assertEquals("account_123_card", AccountDrafts.slug(card.copy(institutionId = "123", last4 = "")))
        assertEquals("amex_us_card_1001", AccountDrafts.slug(card.copy(institutionId = "Amex-US", last4 = "1001")))
    }

    @Test
    fun aDraftNeedsANameAnIssuerAndFourDigits() {
        assertTrue(card.isComplete)
        assertFalse(card.copy(displayName = " ").isComplete)
        assertFalse(card.copy(last4 = "42").isComplete)
        assertFalse(card.copy(currency = "hk").isComplete)
    }

    @Test
    fun aStatementWithoutCardsDraftsABankAccountInItsCurrency() {
        val preview = StagePreview(
            sha256 = "x", filename = "mox.pdf", institutionHint = "mox",
            totals = mapOf("HKD" to InOut(rows = 2), "JPY" to InOut(rows = 9)),
        )
        val draft = AccountDrafts.from(preview, emptyList())
        assertEquals("Mox JPY", draft.displayName)
        assertEquals("checking", draft.accountType)
        assertEquals("JPY", draft.currency)
    }

    @Test
    fun thePickerListsTheIssuersAccountsFirstAndHidesClosedOnes() {
        val accounts = listOf(
            Account("amex", "amex_hk", "Amex", "credit_card", "HKD"),
            Account("old", "hsbc_hk", "Closed", "credit_card", "HKD", closedOn = "2025-01-01"),
            Account("savings", "hsbc_hk", "HSBC Savings", "savings", "HKD"),
        )
        assertEquals(listOf("savings", "amex"), AccountDrafts.ordered(accounts, "hsbc_hk").map { it.id })
    }

    @Test
    fun theDraftCardIsTheOneOnMostRows() {
        val preview = StagePreview(
            sha256 = "x", filename = "card.pdf", institutionHint = "hsbc_hk",
            cards = listOf(StatementCard("1111", "SUPP", rows = 1), StatementCard("4242", "ALEX SAMPLE", rows = 5)),
        )
        assertEquals("4242", AccountDrafts.from(preview, emptyList()).last4)
        assertEquals("HSBC HK card ··4242", AccountDrafts.from(preview, emptyList()).displayName)
    }
}
