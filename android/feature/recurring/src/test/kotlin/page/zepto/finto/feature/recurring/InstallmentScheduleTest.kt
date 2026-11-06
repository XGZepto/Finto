package page.zepto.finto.feature.recurring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.InstallmentCharge
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money

class InstallmentScheduleTest {

    private fun plan(
        term: Int = 6,
        start: String = "2026-01-31",
        status: String = "active",
        paidCount: Int = 0,
        settledEarly: Boolean = false,
        confidence: Double = 1.0,
        confirmed: Boolean = true,
        charges: List<InstallmentCharge> = emptyList(),
    ) = InstallmentPlan(
        id = "plan-1",
        accountId = "hsbc_visa",
        merchant = "Apple Store",
        principal = Money(-600_000, "HKD"),
        termMonths = term,
        startDate = start,
        status = status,
        confidence = confidence,
        isConfirmed = confirmed,
        paidCount = paidCount,
        settledEarly = settledEarly,
        outstanding = Money(-100_000L * (term - paidCount), "HKD"),
        perInstallment = Money(-100_000, "HKD"),
        charges = charges,
    )

    private fun charge(id: String, date: String, seq: Int?, amount: Long = -100_000) =
        InstallmentCharge(id = id, txnDate = date, descriptionRaw = "APPLE STORE ${seq ?: "SETTLEMENT"}", amountBooked = amount, currencyBooked = "HKD", installmentSeq = seq, isSettlement = seq == null)

    @Test
    fun chargesHeldThenEarlierMonthsBilledThenTheRestProjected() {
        val rows = InstallmentSchedule.of(
            plan(charges = listOf(charge("c3", "2026-03-30", 3), charge("c4", "2026-04-29", 4))),
        )
        assertEquals((1..6).toList(), rows.map { it.seq })
        assertEquals(
            listOf(ScheduleState.Billed, ScheduleState.Billed, ScheduleState.Charged, ScheduleState.Charged, ScheduleState.Projected, ScheduleState.Projected),
            rows.map { it.state },
        )
        assertEquals(listOf("prior-1", "prior-2", "charge-c3", "charge-c4", "due-5", "due-6"), rows.map { it.key })
        // Inferred rows fall on the start date's day of month, clamped to short months.
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-30", "2026-04-29", "2026-05-31", "2026-06-30"), rows.map { it.date })
        assertEquals("c3", rows[2].txnId)
        assertNull(rows[0].txnId)
        assertEquals(Money(-100_000, "HKD"), rows[0].amount)
        assertTrue(rows[1].paid)
        assertFalse(rows[4].paid)
    }

    @Test
    fun aChargedRowKeepsItsOwnDateAndAmount() {
        val row = InstallmentSchedule.of(plan(charges = listOf(charge("c1", "2026-02-02", 1, amount = -100_050)))).first()
        assertEquals("2026-02-02", row.date)
        assertEquals(Money(-100_050, "HKD"), row.amount)
        assertEquals("APPLE STORE 1", row.description)
    }

    @Test
    fun aCompletedPlanProjectsNothingAndListsItsSettlement() {
        val rows = InstallmentSchedule.of(
            plan(
                status = "completed",
                paidCount = 2,
                settledEarly = true,
                charges = listOf(charge("c1", "2026-01-31", 1), charge("c2", "2026-02-28", 2), charge("s", "2026-03-10", null, amount = -400_000)),
            ),
        )
        assertEquals(listOf("charge-c1", "charge-c2", "settlement-s"), rows.map { it.key })
        val settlement = rows.last()
        assertEquals(ScheduleState.Settled, settlement.state)
        assertNull(settlement.seq)
        assertEquals(Money(-400_000, "HKD"), settlement.amount)
        assertEquals("s", settlement.txnId)
    }

    @Test
    fun aRepeatedSequenceKeepsTheLaterCharge() {
        val rows = InstallmentSchedule.of(plan(term = 2, charges = listOf(charge("a", "2026-01-31", 1), charge("b", "2026-02-01", 1))))
        assertEquals("charge-b", rows.first().key)
    }

    @Test
    fun withNoChargesEveryMonthIsProjected() {
        val rows = InstallmentSchedule.of(plan(term = 3, start = "2026-11-15"))
        assertTrue(rows.all { it.state == ScheduleState.Projected })
        assertEquals(listOf("2026-11-15", "2026-12-15", "2027-01-15"), rows.map { it.date })
    }

    @Test
    fun addMonthsClampsToTheEndOfTheMonth() {
        assertEquals("2024-02-29", InstallmentSchedule.addMonths("2024-01-31", 1))
        assertEquals("2026-02-28", InstallmentSchedule.addMonths("2026-01-31", 1))
        assertEquals("2026-01-31", InstallmentSchedule.addMonths("2026-01-31", 0))
        assertEquals("2027-03-31", InstallmentSchedule.addMonths("2026-12-31", 3))
    }

    @Test
    fun progressIsFullOnceCompleted() {
        assertEquals(0.5f, InstallmentSchedule.progress(plan(term = 12, paidCount = 6)), 1e-6f)
        assertEquals(1f, InstallmentSchedule.progress(plan(term = 12, paidCount = 4, status = "completed")), 1e-6f)
        assertEquals(0f, InstallmentSchedule.progress(plan(term = 0, paidCount = 0)), 1e-6f)
    }

    @Test
    fun progressLabelSaysWhenSettledEarly() {
        assertEquals("4/12", InstallmentSchedule.progressLabel(plan(term = 12, paidCount = 4)))
        assertEquals("Settled 4/12", InstallmentSchedule.progressLabel(plan(term = 12, paidCount = 4, settledEarly = true, status = "completed")))
    }

    @Test
    fun confidenceShowsOnlyForUnconfirmedGuesses() {
        assertTrue(InstallmentSchedule.isUncertain(plan(confidence = 0.82, confirmed = false)))
        assertFalse(InstallmentSchedule.isUncertain(plan(confidence = 0.95, confirmed = false)))
        assertFalse(InstallmentSchedule.isUncertain(plan(confidence = 0.5, confirmed = true)))
        assertEquals("82%", InstallmentSchedule.confidenceLabel(plan(confidence = 0.824)))
    }

    @Test
    fun aRealChargeOpensTheBlotterOnItsDayAndAccount() {
        val plan = plan()
        val row = InstallmentSchedule.of(plan.copy(charges = listOf(charge("c1", "2026-02-02", 1)))).first()
        assertEquals(LedgerFilter(accounts = listOf("hsbc_visa"), from = "2026-02-02", to = "2026-02-02"), scheduleFilter(plan, row))
    }
}
