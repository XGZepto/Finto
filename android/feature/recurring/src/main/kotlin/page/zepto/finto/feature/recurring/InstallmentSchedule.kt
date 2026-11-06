package page.zepto.finto.feature.recurring

import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Money
import java.time.LocalDate

/** Where one row of a plan's schedule comes from. */
enum class ScheduleState(val label: String) {
    /** A charge in the ledger. */
    Charged("Charged"),

    /** Before the latest billed instalment, so already charged, though that statement was never imported. */
    Billed("Billed"),

    /** Still to come. */
    Projected("Projected"),

    /** Paid off in one charge outside the sequence. */
    Settled("Settled"),
}

data class ScheduleRow(
    val key: String,
    /** 1-based instalment number; null for a settlement. */
    val seq: Int?,
    val date: String,
    val amount: Money,
    val state: ScheduleState,
    val description: String? = null,
    /** The ledger row behind it, when there is one. */
    val txnId: String? = null,
) {
    val paid: Boolean get() = state != ScheduleState.Projected
}

/**
 * The full term of a plan as the web builds it: charges held, then the ones
 * still to come, then any settlement.
 *
 * Remaining instalments are the predictable part of a forecast: amount and
 * date are both agreed. Months before the latest billed sequence count as
 * charged even when that statement was never imported.
 */
object InstallmentSchedule {

    fun of(plan: InstallmentPlan): List<ScheduleRow> {
        val bySeq = plan.charges.filter { it.installmentSeq != null }.associateBy { it.installmentSeq!! }
        val maxSeq = bySeq.keys.maxOrNull()?.coerceAtLeast(0) ?: 0

        val rows = mutableListOf<ScheduleRow>()
        for (seq in 1..plan.termMonths) {
            val charge = bySeq[seq]
            when {
                charge != null -> rows += ScheduleRow(
                    key = "charge-${charge.id}",
                    seq = seq,
                    date = charge.txnDate,
                    amount = charge.amount,
                    state = ScheduleState.Charged,
                    description = charge.descriptionRaw,
                    txnId = charge.id,
                )
                seq <= maxSeq -> rows += ScheduleRow(
                    key = "prior-$seq",
                    seq = seq,
                    date = addMonths(plan.startDate, seq - 1),
                    amount = plan.perInstallment,
                    state = ScheduleState.Billed,
                )
                plan.status != "completed" -> rows += ScheduleRow(
                    key = "due-$seq",
                    seq = seq,
                    date = addMonths(plan.startDate, seq - 1),
                    amount = plan.perInstallment,
                    state = ScheduleState.Projected,
                )
            }
        }
        plan.charges.filter { it.isSettlement }.forEach { charge ->
            rows += ScheduleRow(
                key = "settlement-${charge.id}",
                seq = null,
                date = charge.txnDate,
                amount = charge.amount,
                state = ScheduleState.Settled,
                description = charge.descriptionRaw,
                txnId = charge.id,
            )
        }
        return rows
    }

    /** Same day of the month, clamped to the length of the target month. */
    fun addMonths(iso: String, months: Int): String = LocalDate.parse(iso.take(10)).plusMonths(months.toLong()).toString()

    /** Bar fill for a plan: full once completed, otherwise instalments billed over the term. */
    fun progress(plan: InstallmentPlan): Float =
        if (plan.status == "completed") 1f else (plan.paidCount.toFloat() / plan.termMonths.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** `4/12`, or `Settled 4/12` for a plan paid off early. */
    fun progressLabel(plan: InstallmentPlan): String =
        "${plan.paidCount}/${plan.termMonths}".let { if (plan.settledEarly) "Settled $it" else it }

    /** Below 95% and unconfirmed, the grouping was a judgement call, so its confidence is shown. */
    fun isUncertain(plan: InstallmentPlan): Boolean = !plan.isConfirmed && plan.confidence < 0.95

    fun confidenceLabel(plan: InstallmentPlan): String = "${Math.round(plan.confidence * 100)}%"
}
