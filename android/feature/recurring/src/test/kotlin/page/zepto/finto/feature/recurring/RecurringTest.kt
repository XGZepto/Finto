package page.zepto.finto.feature.recurring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.testing.TestData
import java.time.LocalDate

class RecurringTest {

    private val today = LocalDate.parse("2026-11-06")
    private var next = 0

    private fun charge(date: String, amount: Long, merchant: String? = "Netflix", description: String = "NETFLIX.COM", category: String? = "subscriptions", currency: String = "HKD"): Txn =
        TestData.txn(id = "t${next++}", date = date, description = description, amount = amount, merchant = merchant, category = category)
            .let { if (currency == "HKD") it else it.copy(booked = Money(amount, currency)) }

    /** Charges every [days] days, the last one on [last]. */
    private fun series(merchant: String, amount: Long, days: Long, count: Int, last: String = "2026-10-20") =
        (0 until count).map { charge(LocalDate.parse(last).minusDays(days * (count - 1 - it)).toString(), amount, merchant) }

    private fun plan(
        id: String,
        principal: Long,
        term: Int,
        merchant: String? = "Apple Store",
        description: String = "APPLE STORE INSTALMENT",
    ) = InstallmentPlan(
        id = id,
        accountId = "hsbc_visa",
        merchant = merchant,
        description = description,
        principal = Money(principal, "HKD"),
        termMonths = term,
        startDate = "2026-03-15",
        outstanding = Money(0, "HKD"),
        perInstallment = Money(principal / maxOf(1, term), "HKD"),
    )

    // --- median ----------------------------------------------------------------

    @Test
    fun medianOfOddCountIsTheMiddleValue() {
        assertEquals(30L, Recurring.median(listOf(31, 28, 30)))
    }

    @Test
    fun medianOfEvenCountRoundsTheMeanOfTheMiddleTwoHalfUp() {
        assertEquals(30L, Recurring.median(listOf(29, 31, 28, 32)))
        assertEquals(31L, Recurring.median(listOf(30, 31)))
        assertEquals(1050L, Recurring.median(listOf(1000, 1100)))
    }

    @Test
    fun medianOfNothingIsZero() {
        assertEquals(0L, Recurring.median(emptyList()))
    }

    // --- cadence bands ---------------------------------------------------------

    @Test
    fun cadenceBandsMatchTheWeb() {
        val cases = mapOf(
            4L to Cadence.Irregular,
            5L to Cadence.Weekly,
            7L to Cadence.Weekly,
            9L to Cadence.Weekly,
            10L to Cadence.Irregular,
            23L to Cadence.Irregular,
            24L to Cadence.Monthly,
            31L to Cadence.Monthly,
            38L to Cadence.Monthly,
            39L to Cadence.Irregular,
            79L to Cadence.Irregular,
            80L to Cadence.Quarterly,
            100L to Cadence.Quarterly,
            101L to Cadence.Irregular,
            339L to Cadence.Irregular,
            340L to Cadence.Yearly,
            365L to Cadence.Yearly,
            400L to Cadence.Yearly,
            401L to Cadence.Irregular,
        )
        cases.forEach { (days, cadence) -> assertEquals("$days days", cadence, Recurring.cadenceFor(days, dates = 3)) }
    }

    @Test
    fun aSingleDateIsIrregular() {
        assertEquals(Cadence.Irregular, Recurring.cadenceFor(30, dates = 1))
        val only = Recurring.commitments(listOf(charge("2026-10-01", -7800)), today).single()
        assertEquals(Cadence.Irregular, only.cadence)
        assertNull(only.monthly)
        assertNull(only.nextDue)
        assertFalse(only.inactive)
    }

    // --- merge -----------------------------------------------------------------

    @Test
    fun mergeCountsAChargeWithBothTagsOnceKeepingItsFirstPosition() {
        val a = charge("2026-10-01", -100)
        val b = charge("2026-09-01", -100)
        val c = charge("2026-08-01", -100)
        val tagged = a.copy(tags = listOf("subscription", "recurring"))
        val merged = Recurring.merge(listOf(listOf(a, b), listOf(c, tagged)))
        assertEquals(listOf(a.id, b.id, c.id), merged.map { it.id })
        assertEquals("the later copy replaces the value", tagged, merged.first())
    }

    // --- commitments -------------------------------------------------------------

    @Test
    fun monthlyChargesBecomeOneCommitmentWithTypicalAndMonthly() {
        val rows = listOf(
            charge("2026-10-03", -7800),
            charge("2026-09-03", -7800),
            charge("2026-08-03", -6800),
            charge("2026-07-03", -7800),
        )
        val netflix = Recurring.commitments(rows, LocalDate.parse("2026-10-20")).single()
        assertEquals("netflix", netflix.key)
        assertEquals("Netflix", netflix.merchant)
        assertEquals(Cadence.Monthly, netflix.cadence)
        assertEquals(Money(-7800, "HKD"), netflix.amount)
        assertEquals(Money(-7800, "HKD"), netflix.monthly)
        assertEquals("2026-10-03", netflix.lastCharge)
        // Gaps 31, 31, 30 → median 31.
        assertEquals("2026-11-03", netflix.nextDue)
        assertFalse(netflix.inactive)
        assertEquals(Money(-30_200, "HKD"), netflix.totalPaid)
        assertEquals(Money(-7550, "HKD"), netflix.average)
        assertEquals("2026-07-03", netflix.firstSeen)
        assertEquals(4, netflix.count)
        assertEquals("subscriptions", netflix.category)
    }

    @Test
    fun monthlyEquivalentRoundsPerCadence() {
        val weekly = Recurring.commitments(series("Gym", -1000, 7, 4), today).single()
        assertEquals(Cadence.Weekly, weekly.cadence)
        // 1000 × 30/7 = 4285.71…
        assertEquals(Money(-4286, "HKD"), weekly.monthly)

        val quarterly = Recurring.commitments(series("Insurance", -3001, 91, 3), today).single()
        assertEquals(Cadence.Quarterly, quarterly.cadence)
        assertEquals(Money(-1000, "HKD"), quarterly.monthly)

        val yearly = Recurring.commitments(series("Domain", -1000, 365, 2), today).single()
        assertEquals(Cadence.Yearly, yearly.cadence)
        assertEquals(Money(-83, "HKD"), yearly.monthly)
    }

    @Test
    fun groupsByTrimmedLowerCaseMerchantFallingBackToDescription() {
        val rows = listOf(
            charge("2026-10-01", -100, merchant = " Spotify "),
            charge("2026-09-01", -100, merchant = "spotify"),
            charge("2026-10-02", -200, merchant = null, description = "ICLOUD STORAGE"),
            // JavaScript's `||` falls through an empty merchant to the description.
            charge("2026-09-02", -200, merchant = "", description = "icloud storage"),
            // Nothing to group by: skipped.
            charge("2026-09-03", -300, merchant = null, description = "   "),
        )
        val commitments = Recurring.commitments(rows, today)
        assertEquals(setOf("spotify", "icloud storage"), commitments.map { it.key }.toSet())
        assertEquals(" Spotify ", commitments.first { it.key == "spotify" }.merchant)
        assertEquals("ICLOUD STORAGE", commitments.first { it.key == "icloud storage" }.merchant)
    }

    @Test
    fun twoChargesOnOneDayCountTwiceButMakeOneGap() {
        val rows = listOf(
            charge("2026-10-01", -500),
            charge("2026-10-01", -500),
            charge("2026-09-01", -500),
        )
        val c = Recurring.commitments(rows, LocalDate.parse("2026-10-10")).single()
        assertEquals(Cadence.Monthly, c.cadence)
        assertEquals(3, c.count)
        assertEquals(Money(-1500, "HKD"), c.totalPaid)
        assertEquals("2026-10-31", c.nextDue)
    }

    @Test
    fun inactiveOnlyWhenMoreThanOneIntervalOverdue() {
        // Monthly, every 30 days, last on Aug 1 → due Aug 31.
        val rows = series("Disney+", -6300, 30, 3, last = "2026-08-01")
        val dueDate = LocalDate.parse("2026-08-31")
        assertEquals("2026-08-31", Recurring.commitments(rows, dueDate).single().nextDue)
        assertFalse("due today", Recurring.commitments(rows, dueDate).single().inactive)
        assertFalse("exactly one interval late", Recurring.commitments(rows, dueDate.plusDays(30)).single().inactive)
        assertTrue("more than one interval late", Recurring.commitments(rows, dueDate.plusDays(31)).single().inactive)
    }

    @Test
    fun typicalIsTheMedianSoOneOddChargeCannotMoveIt() {
        val rows = listOf(
            charge("2026-10-01", -5000),
            charge("2026-09-01", -5000),
            charge("2026-08-01", -50_000),
        )
        assertEquals(Money(-5000, "HKD"), Recurring.commitments(rows, today).single().amount)
    }

    @Test
    fun irregularIntervalsStayOutOfTheMonthlyTotal() {
        val rows = listOf(charge("2026-10-01", -100), charge("2026-09-16", -100), charge("2026-08-01", -100))
        val c = Recurring.commitments(rows, today).single()
        // Gaps 15 and 46 → median 31 (rounded 30.5): monthly after all.
        assertEquals(Cadence.Monthly, c.cadence)
        val odd = Recurring.commitments(listOf(charge("2026-10-01", -100), charge("2026-08-15", -100)), today).single()
        assertEquals(Cadence.Irregular, odd.cadence)
        assertNull(odd.monthly)
        assertNull(odd.nextDue)
    }

    @Test
    fun sortedByMonthlyCostThenByTypicalCharge() {
        val rows = series("Cheap", -1000, 30, 3) +
            series("Dear", -9000, 30, 3) +
            series("Annual", -50_000, 365, 2) +
            listOf(charge("2026-09-01", -20_000, merchant = "One-off"), charge("2026-10-01", -100, merchant = "Tiny one-off"))
        val order = Recurring.commitments(rows, LocalDate.parse("2026-10-25")).map { it.merchant }
        // Annual: 50000/12 = 4167 a month.
        assertEquals(listOf("Dear", "Annual", "Cheap", "One-off", "Tiny one-off"), order)
    }

    @Test
    fun theFirstRowGivesCurrencyAndCategory() {
        val rows = listOf(
            charge("2026-10-01", -999, currency = "USD", category = "software"),
            charge("2026-09-01", -999, currency = "USD", category = null),
        )
        val c = Recurring.commitments(rows, today).single()
        assertEquals("USD", c.amount.currency)
        assertEquals("software", c.category)
    }

    // --- plans and the filtered view ------------------------------------------------

    @Test
    fun planPerMonthSpreadsThePrincipalAcrossTheTerm() {
        assertEquals(Money(-1000, "HKD"), Recurring.perMonth(plan("p", -12_000, 12)))
        assertEquals(Money(-3333, "HKD"), Recurring.perMonth(plan("p", -10_000, 3)))
        assertEquals(Money(-6667, "HKD"), Recurring.perMonth(plan("p", -20_000, 3)))
        assertEquals("a zero term counts as one", Money(-500, "HKD"), Recurring.perMonth(plan("p", -500, 0)))
    }

    private val mixed: List<Commitment> by lazy {
        Recurring.commitments(
            series("Netflix", -7800, 30, 4, last = "2026-10-20") +
                series("Lapsed Gym", -40_000, 30, 3, last = "2026-05-01") +
                listOf(charge("2026-10-01", -1000, merchant = "Parking"), charge("2026-08-12", -1000, merchant = "Parking")),
            today,
        )
    }

    private val plans = listOf(plan("iphone", -12_000, 12), plan("sofa", -6_000, 6, merchant = null, description = "IKEA EASY PAY"))

    @Test
    fun theViewSplitsActiveAndInactiveAndTotalsTheMonth() {
        val view = Recurring.view(mixed, plans, RecurringFilter(), "HKD")
        assertEquals(listOf("Netflix", "Parking"), view.activeSubscriptions.map { it.merchant })
        assertEquals(listOf("Lapsed Gym"), view.inactiveSubscriptions.map { it.merchant })
        assertEquals(2, view.plans.size)
        assertEquals(1, view.irregularCount)
        // Netflix 7800 + plans 1000 + 1000; Parking is irregular and the gym inactive.
        assertEquals(Money(-9800, "HKD"), view.monthlyTotal)
    }

    @Test
    fun kindLimitsTheLists() {
        val subs = Recurring.view(mixed, plans, RecurringFilter(kind = RecurringKind.Subscriptions), "HKD")
        assertTrue(subs.plans.isEmpty())
        assertEquals(Money(-7800, "HKD"), subs.monthlyTotal)

        val instalments = Recurring.view(mixed, plans, RecurringFilter(kind = RecurringKind.Instalments), "HKD")
        assertTrue(instalments.activeSubscriptions.isEmpty() && instalments.inactiveSubscriptions.isEmpty())
        assertEquals(Money(-2000, "HKD"), instalments.monthlyTotal)
    }

    @Test
    fun cadenceKeepsPlansOnlyForAnyOrMonthly() {
        val monthly = Recurring.view(mixed, plans, RecurringFilter(cadence = Cadence.Monthly), "HKD")
        assertEquals(listOf("Netflix"), monthly.activeSubscriptions.map { it.merchant })
        assertEquals(2, monthly.plans.size)

        val irregular = Recurring.view(mixed, plans, RecurringFilter(cadence = Cadence.Irregular), "HKD")
        assertEquals(listOf("Parking"), irregular.activeSubscriptions.map { it.merchant })
        assertTrue(irregular.plans.isEmpty())
        assertEquals(Money(0, "HKD"), irregular.monthlyTotal)
        assertEquals(1, irregular.irregularCount)

        assertTrue(Recurring.view(mixed, plans, RecurringFilter(cadence = Cadence.Yearly), "HKD").isEmpty)
    }

    @Test
    fun searchMatchesMerchantsAndPlanNamesIgnoringCase() {
        val view = Recurring.view(mixed, plans, RecurringFilter(search = "  NET "), "HKD")
        assertEquals(listOf("Netflix"), view.activeSubscriptions.map { it.merchant })
        assertTrue(view.plans.isEmpty())

        val ikea = Recurring.view(mixed, plans, RecurringFilter(search = "ikea"), "HKD")
        assertEquals(listOf("sofa"), ikea.plans.map { it.id })
        assertEquals("the total follows the filter", Money(-1000, "HKD"), ikea.monthlyTotal)
    }

    @Test
    fun theTotalIsLabelledInTheReportingCurrency() {
        assertEquals("USD", Recurring.view(mixed, plans, RecurringFilter(), "USD").monthlyTotal.currency)
    }
}
