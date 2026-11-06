package page.zepto.finto.feature.imports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ReconcileSummary

/** What reconciliation changed, in the web's words; notable counts need a decision. */
data class Change(val label: String, val value: Int, val notable: Boolean)

object ImportLabels {
    fun changes(summary: ReconcileSummary?): List<Change> = summary?.let { r ->
        listOf(
            Change("Transactions in ledger", r.transactions, false),
            Change("Duplicates merged", r.duplicatesMerged, false),
            Change("Duplicates to review", r.duplicateCandidates, true),
            Change("Transfers linked", r.transfersLinked, false),
            Change("Transfers to review", r.transferCandidates, true),
            Change("Instalment plans", r.installmentPlans, false),
            Change("Instalments to review", r.installmentCandidates, true),
            Change("Refunds linked", r.refundsLinked, false),
        ).filter { it.value != 0 }
    }.orEmpty()

    /** A maintenance result's non-zero counts, keys spelt out. */
    fun counts(result: JsonObject): List<Pair<String, String>> = result.entries.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        if (primitive.booleanOrNull != null) return@mapNotNull null
        val number = primitive.longOrNull
        when {
            number == 0L -> null
            number != null -> key.replace('_', ' ').replaceFirstChar(Char::uppercase) to number.toString()
            primitive.isString && primitive.content.isNotBlank() -> key.replace('_', ' ').replaceFirstChar(Char::uppercase) to primitive.content
            else -> null
        }
    }

    fun categorised(review: ImportReview): Int =
        listOf("rule", "rules", "model", "manual").sumOf { review.categories.bySource[it] ?: 0 }

    fun share(part: Int, whole: Int): String = if (whole <= 0) "0%" else "${Math.round(part * 100.0 / whole)}%"

    fun reviewStatus(status: String?): String = when (status) {
        "clean" -> "Clean"
        "attention" -> "Needs a look"
        else -> "Review"
    }
}
