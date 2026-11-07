package page.zepto.finto.feature.imports

import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Institution
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.NewAccount
import page.zepto.finto.core.model.StagePreview
import java.util.Locale

/** The new-account form, filled in from what the statement printed. */
data class AccountDraft(
    val displayName: String,
    val institutionId: String,
    val accountType: String,
    val currency: String,
    val last4: String = "",
    val cardholder: String = "",
) {
    val isCard: Boolean get() = accountType == "credit_card" || accountType == "charge_card"

    val isComplete: Boolean
        get() = displayName.isNotBlank() && institutionId.isNotBlank() &&
            CURRENCY.matches(currency) && (last4.isEmpty() || LAST4.matches(last4))

    private companion object {
        val CURRENCY = Regex("[A-Z]{3}")
        val LAST4 = Regex("\\d{4}")
    }
}

object AccountDrafts {
    /** The account types the ledger accepts, cards first since statements mostly come from them. */
    val TYPES = listOf("credit_card", "charge_card", "checking", "savings", "multi_currency", "investment", "loan")

    fun typeLabel(type: String): String = when (type) {
        "credit_card" -> "Credit card"
        "charge_card" -> "Charge card"
        "checking" -> "Current account"
        "multi_currency" -> "Multi-currency"
        else -> Labels.humanize(type)
    }

    /**
     * A draft for the statement in [preview]: its issuer, the card printed on
     * most rows and the currency most rows are in. The person can change any of it.
     */
    fun from(preview: StagePreview, institutions: List<Institution>): AccountDraft {
        val card = preview.cards.maxByOrNull { it.rows }
        val institutionId = preview.institutionHint.orEmpty()
        val issuer = institutions.firstOrNull { it.id == institutionId }?.displayName
            ?: institutionId.takeIf { it.isNotEmpty() }?.let(Labels::humanize)
        val currency = preview.totals.maxByOrNull { it.value.rows }?.key ?: "HKD"
        val name = when {
            card != null -> listOfNotNull(issuer, "card ··${card.last4}").joinToString(" ")
            issuer != null -> "$issuer $currency"
            else -> ""
        }
        return AccountDraft(
            displayName = name,
            institutionId = institutionId,
            accountType = if (card != null) "credit_card" else "checking",
            currency = currency,
            last4 = card?.last4.orEmpty(),
            cardholder = card?.holder.orEmpty(),
        )
    }

    /** The request for [draft], with an id none of [existing] already uses. */
    fun toNewAccount(draft: AccountDraft, existing: Collection<Account>): NewAccount {
        val base = slug(draft)
        val taken = existing.mapTo(HashSet()) { it.id }
        val id = generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "${base.take(60)}_$it" }
            .first { it !in taken }
        return NewAccount(
            id = id,
            institutionId = draft.institutionId,
            displayName = draft.displayName.trim(),
            accountType = draft.accountType,
            primaryCurrency = draft.currency,
            last4 = draft.last4.takeIf { draft.isCard && it.isNotEmpty() },
            cardholderName = draft.cardholder.trim().takeIf { draft.isCard && it.isNotEmpty() },
        )
    }

    /** A lowercase id the server accepts, such as `hsbc_hk_card_3757`. */
    internal fun slug(draft: AccountDraft): String {
        val kind = if (draft.isCard) "card" else draft.accountType
        val raw = listOf(draft.institutionId, kind, draft.last4.takeIf { draft.isCard }.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString("_")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        val leading = if (raw.firstOrNull()?.isLetter() == true) raw else "account_$raw".trimEnd('_')
        return leading.take(64)
    }

    /** Open accounts, the statement's issuer first, for the picker. */
    fun ordered(accounts: List<Account>, institutionHint: String?): List<Account> =
        accounts.filter { it.isOpen }
            .sortedWith(compareBy<Account> { it.institutionId != institutionHint }.thenBy { it.displayName.lowercase(Locale.ROOT) })
}
