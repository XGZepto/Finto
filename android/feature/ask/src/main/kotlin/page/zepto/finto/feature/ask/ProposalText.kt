package page.zepto.finto.feature.ask

import page.zepto.finto.core.model.ChangeFields
import page.zepto.finto.core.model.ChangeProposal
import page.zepto.finto.core.model.Labels

/** A proposed correction in words, as the web app says it. */
object ProposalText {
    private val kinds = mapOf(
        "cc_payment" to "Card payment",
        "fx_conversion" to "Currency exchange",
        "atm" to "Cash withdrawal",
    )

    private fun kind(value: String) = kinds[value] ?: Labels.humanize(value)

    private fun category(category: String?, subcategory: String?): String = when {
        category == null -> "Uncategorised"
        subcategory != null -> "${Labels.humanize(category)} › ${Labels.humanize(subcategory)}"
        else -> Labels.humanize(category)
    }

    /** What a proposal sets, in one line: "Card payment · Transfers › Card payment · +bill". */
    fun change(p: ChangeProposal): String = buildList {
        p.set.kind?.let { add(kind(it)) }
        p.set.category?.let { add(category(it, p.set.subcategory)) }
        p.set.merchant?.let { add(if (it.isBlank()) "No merchant" else "Merchant “$it”") }
        p.set.reviewState?.let { add(Labels.humanize(it)) }
        addAll(p.addTags.map { "+$it" })
        addAll(p.removeTags.map { "−$it" })
    }.joinToString(" · ")

    /** One row's before → after, naming only what moves. */
    fun row(before: ChangeFields, after: ChangeFields): String = buildList {
        if (before.kind != after.kind) add("${kind(before.kind)} → ${kind(after.kind)}")
        if (before.category != after.category || before.subcategory != after.subcategory) {
            add("${category(before.category, before.subcategory)} → ${category(after.category, after.subcategory)}")
        }
        if (before.merchant != after.merchant) add("${before.merchant ?: "no merchant"} → ${after.merchant ?: "no merchant"}")
        if (before.reviewState != after.reviewState) {
            add("${Labels.humanize(before.reviewState)} → ${Labels.humanize(after.reviewState)}")
        }
        addAll(after.tags.filter { it !in before.tags }.map { "+$it" })
        addAll(before.tags.filter { it !in after.tags }.map { "−$it" })
    }.joinToString(" · ")

    fun status(p: ChangeProposal): String = when (p.status) {
        "applied" -> "Applied"
        "undone" -> "Undone"
        "dismissed" -> "Dismissed"
        else -> "Proposed change"
    }

    fun rows(count: Int) = if (count == 1) "1 row" else "$count rows"
}
