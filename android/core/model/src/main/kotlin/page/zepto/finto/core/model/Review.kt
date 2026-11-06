package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

/** The three matching queues the linker could not decide alone. */
enum class ReviewQueue(val path: String, val title: String) {
    Duplicates("duplicates", "Duplicates"),
    Transfers("transfers", "Transfers"),
    Installments("installments", "Instalments"),
}

/** One open suggestion. Which fields are set depends on the queue. */
@Serializable
data class ReviewCandidate(
    val id: String,
    val score: Double = 0.0,
    val reasons: List<String> = emptyList(),
    // Duplicates: the row to keep and the one to drop.
    val keepId: String? = null,
    val keepDate: String? = null,
    val keepAccount: String? = null,
    val keepDesc: String? = null,
    val keepAmount: Long? = null,
    val keepCurrency: String? = null,
    val dupeId: String? = null,
    val dupeDate: String? = null,
    val dupeAccount: String? = null,
    val dupeDesc: String? = null,
    val dupeAmount: Long? = null,
    val dupeCurrency: String? = null,
    // Transfers: money out of one account and into another.
    val dateDelta: Int? = null,
    val amountDelta: Long? = null,
    val outId: String? = null,
    val outDate: String? = null,
    val outAccount: String? = null,
    val outDesc: String? = null,
    val outAmount: Long? = null,
    val outCurrency: String? = null,
    val inId: String? = null,
    val inDate: String? = null,
    val inAccount: String? = null,
    val inDesc: String? = null,
    val inAmount: Long? = null,
    val inCurrency: String? = null,
    // Instalments: a run of charges that looks like one plan.
    val description: String? = null,
    val termMonths: Int? = null,
    val accountId: String? = null,
    val transactions: List<CandidateRow> = emptyList(),
) {
    val title: String get() = keepDesc ?: outDesc ?: description ?: id
}

@Serializable
data class CandidateRow(
    val id: String,
    val txnDate: String,
    val descriptionRaw: String = "",
    val amountBooked: Long,
    val currencyBooked: String,
) {
    val amount: Money get() = Money(amountBooked, currencyBooked)
}

@Serializable
data class ReviewQueueResponse(
    val items: List<ReviewCandidate> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ResolveRequest(val action: String)

@Serializable
data class IntegrityReport(
    val healthy: Boolean = true,
    val violations: List<Violation> = emptyList(),
    val balanceChecks: List<BalanceCheck> = emptyList(),
    val unverifiedAccounts: List<UnverifiedAccount> = emptyList(),
    val summary: IntegritySummary = IntegritySummary(),
)

@Serializable
data class Violation(val check: String, val count: Int = 0, val description: String = "")

/**
 * One statement period compared against the ledger. An `insufficient_data`
 * check carries only the account, status and a note, so the period and the
 * figures are optional.
 */
@Serializable
data class BalanceCheck(
    val accountId: String,
    val accountName: String? = null,
    val periodStart: String = "",
    val periodEnd: String = "",
    val expectedDelta: Money? = null,
    val actualDelta: Money? = null,
    val discrepancy: Money? = null,
    /** `ok`, `discrepancy` or `insufficient_data`. */
    val status: String = "insufficient_data",
    val note: String? = null,
)

@Serializable
data class UnverifiedAccount(val accountId: String, val displayName: String, val txnCount: Int = 0)

@Serializable
data class IntegritySummary(
    val checksRun: Int = 0,
    val discrepancyCount: Int = 0,
    val violationCount: Int = 0,
    val unverifiedAccountCount: Int = 0,
)
