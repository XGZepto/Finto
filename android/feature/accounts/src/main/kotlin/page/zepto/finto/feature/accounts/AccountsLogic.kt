package page.zepto.finto.feature.accounts

import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Card
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Position
import page.zepto.finto.core.model.TypeBalance

/** An account with its balances (one per currency) and how many cards draw on it. */
data class AccountRow(val account: Account, val positions: List<Position>, val cards: Int)

/**
 * A product as the bank sells it: subaccounts sharing a `balance_group`
 * (a multi-currency account's pockets), or a single account on its own.
 */
data class ProductGroup(
    val key: String,
    val name: String,
    val institution: String,
    val children: List<AccountRow>,
    /** Native totals per currency; never summed across currencies. */
    val totals: List<Money>,
) {
    val single: Boolean get() = children.size == 1
    val cards: Int get() = children.sumOf { it.cards }
}

/** A card number history: a card and the cards that replaced it. */
data class CardLineage(val holder: String, val supplementary: Boolean, val numbers: List<String>, val reissued: Boolean)

/** One end of an account's money flow: a category bucket or another account. */
data class FlowNode(val key: String, val label: String, val toAccount: String?, val amount: Long, val moves: Int)

data class AccountFlow(val incoming: List<FlowNode>, val outgoing: List<FlowNode>) {
    val isEmpty: Boolean get() = incoming.isEmpty() && outgoing.isEmpty()
}

/** Money in and out of one account over the whole ledger, in the reporting currency. */
data class AccountFlowRow(val accountId: String, val name: String, val inflow: Money, val outflow: Money)

data class InternalTransfer(val from: String, val fromName: String, val to: String, val toName: String, val amount: Money, val moves: Int)

/** The web app's account arithmetic, kept free of Android so it is tested on the JVM. */
object AccountsLogic {

    fun rows(accounts: List<Account>, positions: List<Position>, cards: List<Card>): List<AccountRow> {
        val held = positions.groupBy { it.accountId }
        return accounts.map { account ->
            AccountRow(account, held[account.id].orEmpty(), cards.count { it.accountId == account.id })
        }
    }

    fun groups(rows: List<AccountRow>): List<ProductGroup> =
        rows.groupBy { it.account.balanceGroup?.takeIf(String::isNotBlank) ?: "account:${it.account.id}" }
            .map { (key, children) ->
                val totals = linkedMapOf<String, Long>()
                children.flatMap { it.positions }.forEach { totals[it.currency] = (totals[it.currency] ?: 0L) + it.balance.amount }
                ProductGroup(
                    key = key,
                    name = if (key.startsWith("account:")) children.first().account.displayName else Labels.humanize(key),
                    institution = children.first().account.institutionId,
                    children = children,
                    totals = totals.map { (currency, amount) -> Money(amount, currency) },
                )
            }
            .sortedWith(compareBy({ it.institution }, { it.name }))

    /** Other subaccounts of the same product, including [id] itself. */
    fun siblings(rows: List<AccountRow>, id: String): List<AccountRow> {
        val current = rows.firstOrNull { it.account.id == id } ?: return emptyList()
        val group = current.account.balanceGroup ?: return listOf(current)
        return rows.filter { it.account.balanceGroup == group }
    }

    fun lineages(cards: List<Card>, accountIds: Collection<String>): List<CardLineage> =
        cards.filter { it.accountId in accountIds }
            .groupBy { it.lineageRoot ?: it.id }
            .values
            .map { chain ->
                val first = chain.first()
                CardLineage(
                    holder = if (first.isSupplementary) first.cardholderName ?: "Supplementary" else "You",
                    supplementary = first.isSupplementary,
                    numbers = chain.mapNotNull { it.last4 },
                    reissued = chain.size > 1,
                )
            }

    /** Where an account's money came from and went, largest seven each way. */
    fun accountFlow(flows: Flows, id: String, names: Map<String, String>): AccountFlow {
        val normalised = flows.normalised ?: return AccountFlow(emptyList(), emptyList())
        val nodes = normalised.externalNodes.filter { it.accountId == id }
        val incoming = nodes.filter { it.inflow.amount > 0 }.map {
            FlowNode("ext-in-${it.bucket}", Labels.humanize(it.bucket), null, it.inflow.amount, it.moves)
        } + normalised.internal.filter { it.toAccount == id }.map {
            FlowNode("int-in-${it.fromAccount}", names[it.fromAccount] ?: it.fromAccount, it.fromAccount, it.amount.amount, it.moves)
        }
        val outgoing = nodes.filter { it.out.amount < 0 }.map {
            FlowNode("ext-out-${it.bucket}", Labels.humanize(it.bucket), null, -it.out.amount, it.moves)
        } + normalised.internal.filter { it.fromAccount == id }.map {
            FlowNode("int-out-${it.toAccount}", names[it.toAccount] ?: it.toAccount, it.toAccount, it.amount.amount, it.moves)
        }
        return AccountFlow(
            incoming.sortedByDescending { it.amount }.take(7),
            outgoing.sortedByDescending { it.amount }.take(7),
        )
    }

    /** Per-account in and out for the Accounts page, largest movers first. */
    fun flowRows(flows: Flows, names: Map<String, String>, currency: String): List<AccountFlowRow> {
        val nodes = flows.normalised?.externalNodes.orEmpty()
        return nodes.groupBy { it.accountId }
            .map { (account, rows) ->
                AccountFlowRow(
                    account,
                    names[account] ?: account,
                    Money(rows.sumOf { it.inflow.amount }, currency),
                    Money(rows.sumOf { it.out.amount }, currency),
                )
            }
            .filter { it.inflow.amount != 0L || it.outflow.amount != 0L }
            .sortedByDescending { maxOf(it.inflow.amount, -it.outflow.amount) }
            .take(14)
    }

    fun internalTransfers(flows: Flows, names: Map<String, String>): List<InternalTransfer> =
        flows.normalised?.internal.orEmpty()
            .map { InternalTransfer(it.fromAccount, names[it.fromAccount] ?: it.fromAccount, it.toAccount, names[it.toAccount] ?: it.toAccount, it.amount, it.moves) }
            .sortedByDescending { it.amount.amount }

    fun assets(byType: List<TypeBalance>, currency: String) = Money(byType.filter { it.balance.amount > 0 }.sumOf { it.balance.amount }, currency)

    fun liabilities(byType: List<TypeBalance>, currency: String) = Money(byType.filter { it.balance.amount < 0 }.sumOf { it.balance.amount }, currency)

    /** `institution · type · ••1234`, as the web's account header reads. */
    fun subtitle(account: Account): String = listOf(
        Labels.humanize(account.institutionId),
        Labels.humanize(account.accountType),
        account.maskedNumber ?: Labels.humanize(account.id),
    ).joinToString(" · ")
}
