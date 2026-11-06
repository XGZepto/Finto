package page.zepto.finto.feature.health

import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/** How sure the matcher was, in the web app's three bands. */
enum class ScoreBand(val label: String) {
    High("High match"),
    Medium("Medium match"),
    Low("Low match"),
}

/** One row of a pair: the duplicate to keep or drop, or one leg of a transfer. */
data class CandidateSide(
    val role: String,
    val id: String,
    val date: String,
    val account: String,
    val description: String,
    val amount: Money,
)

/** The shaping the review screen needs, kept out of Compose so it can be tested. */
object ReviewLogic {

    fun band(score: Double): ScoreBand = when {
        score >= 0.85 -> ScoreBand.High
        score >= 0.7 -> ScoreBand.Medium
        else -> ScoreBand.Low
    }

    /** `0.923` → `92`, as the web prints it. */
    fun percent(score: Double): Int = (score * 100).roundToInt()

    /** The two sides of a pair, whatever the queue calls them; instalments are a group and have none. */
    fun sides(queue: ReviewQueue, item: ReviewCandidate): List<CandidateSide> = when (queue) {
        ReviewQueue.Duplicates -> listOf(
            CandidateSide(
                "Keep", item.keepId.orEmpty(), item.keepDate.orEmpty(), item.keepAccount.orEmpty(),
                item.keepDesc.orEmpty(), Money(item.keepAmount ?: 0, item.keepCurrency.orEmpty()),
            ),
            CandidateSide(
                "Drop", item.dupeId.orEmpty(), item.dupeDate.orEmpty(), item.dupeAccount.orEmpty(),
                item.dupeDesc.orEmpty(), Money(item.dupeAmount ?: 0, item.dupeCurrency.orEmpty()),
            ),
        )
        ReviewQueue.Transfers -> listOf(
            CandidateSide(
                "Out", item.outId.orEmpty(), item.outDate.orEmpty(), item.outAccount.orEmpty(),
                item.outDesc.orEmpty(), Money(item.outAmount ?: 0, item.outCurrency.orEmpty()),
            ),
            CandidateSide(
                "In", item.inId.orEmpty(), item.inDate.orEmpty(), item.inAccount.orEmpty(),
                item.inDesc.orEmpty(), Money(item.inAmount ?: 0, item.inCurrency.orEmpty()),
            ),
        )
        ReviewQueue.Installments -> emptyList()
    }

    /** What accepting a candidate does to the ledger, worded as the web app words it. */
    fun consequence(queue: ReviewQueue): String = when (queue) {
        ReviewQueue.Duplicates -> "Hides the second row as a duplicate. Reversible."
        ReviewQueue.Transfers -> "Links both legs into one transfer."
        ReviewQueue.Installments -> "Groups these charges into one instalment plan."
    }

    /** `2 day gap · HK$0.50 amount gap` for a transfer; null when the matcher recorded no gap. */
    fun gap(item: ReviewCandidate): String? {
        val days = item.dateDelta ?: return null
        val amount = item.amountDelta?.takeIf { it != 0L }?.let { delta ->
            val currency = item.outCurrency ?: item.inCurrency
            val text = if (currency.isNullOrBlank()) abs(delta).toString() else MoneyFormat.format(Money(abs(delta), currency))
            "$text amount gap"
        }
        return listOfNotNull("$days day gap", amount).joinToString(" · ")
    }

    /** The row in the blotter on its own date, transfers and duplicates included so it cannot hide. */
    fun blotterFilter(account: String, date: String): LedgerFilter = LedgerFilter(
        accounts = listOfNotNull(account.takeIf { it.isNotBlank() }),
        from = date.takeIf { it.isNotBlank() }?.take(10),
        to = date.takeIf { it.isNotBlank() }?.take(10),
        includeTransfers = true,
        includeDuplicates = true,
    )

    fun emptyLine(queue: ReviewQueue): String = when (queue) {
        ReviewQueue.Duplicates -> "No duplicates to review."
        ReviewQueue.Transfers -> "No transfers to review."
        ReviewQueue.Installments -> "No instalment runs to review."
    }
}
