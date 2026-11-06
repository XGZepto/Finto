package page.zepto.finto

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.model.CurrencyAmount
import page.zepto.finto.core.model.InstallmentCharge
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Txn
import page.zepto.finto.feature.recurring.InstallmentDetailScreen
import page.zepto.finto.feature.recurring.InstallmentDetailUiState
import page.zepto.finto.feature.recurring.InstallmentSchedule
import page.zepto.finto.feature.recurring.InstallmentsScreen
import page.zepto.finto.feature.recurring.InstallmentsUiState
import page.zepto.finto.feature.recurring.Recurring
import page.zepto.finto.feature.recurring.RecurringDetailScreen
import page.zepto.finto.feature.recurring.RecurringDetailUiState
import page.zepto.finto.feature.recurring.RecurringFilter
import page.zepto.finto.feature.recurring.RecurringScreen
import page.zepto.finto.feature.recurring.RecurringUiState
import page.zepto.finto.ui.TopLevelDestination
import java.time.LocalDate

/** Recurring charges and instalment plans, from fixed invented data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RecurringScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        compose.captureScreen(name, dark, TopLevelDestination.More, content)

    private val today = LocalDate.parse("2026-11-06")
    private var next = 0

    private fun hkd(amount: Long) = Money(amount, "HKD")

    private fun charges(merchant: String, amount: Long, everyDays: Long, count: Int, last: String, category: String, account: String = "HSBC Visa Signature"): List<Txn> =
        (0 until count).map { index ->
            Txn(
                id = "t${next++}",
                date = LocalDate.parse(last).minusDays(everyDays * index).toString(),
                accountId = account.lowercase().replace(' ', '_'),
                accountName = account,
                accountCurrency = "HKD",
                institutionId = "hsbc_hk",
                description = merchant.uppercase(),
                merchant = merchant,
                booked = hkd(amount),
                kind = "purchase",
                category = category,
                tags = listOf("subscription"),
            )
        }

    private val charges: List<Txn> by lazy {
        charges("Netflix", -9_300, 30, 14, "2026-10-28", "entertainment") +
            charges("Spotify Premium", -5_900, 30, 18, "2026-10-21", "entertainment", account = "Amex Platinum") +
            charges("iCloud+", -2_300, 30, 9, "2026-11-01", "software") +
            charges("PureGym", -12_000, 7, 10, "2026-11-03", "health") +
            charges("Adobe Creative Cloud", -598_800, 365, 2, "2026-08-15", "software") +
            charges("Octopus parking", -4_500, 11, 1, "2026-10-12", "transport") +
            charges("Octopus parking", -6_200, 1, 1, "2026-09-02", "transport") +
            charges("Disney+", -6_300, 30, 6, "2026-05-02", "entertainment") +
            charges("Headspace", -8_800, 30, 4, "2026-03-19", "health")
    }

    private fun plan(
        id: String,
        merchant: String,
        principal: Long,
        term: Int,
        paid: Int,
        start: String,
        account: String = "hsbc_visa_signature",
        status: String = "active",
        confidence: Double = 1.0,
        confirmed: Boolean = true,
        settledEarly: Boolean = false,
        currency: String = "HKD",
    ): InstallmentPlan {
        val per = principal / term
        val remaining = if (status == "completed") 0 else term - paid
        return InstallmentPlan(
            id = id,
            accountId = account,
            merchant = merchant,
            description = "$merchant instalment",
            principal = Money(principal, currency),
            termMonths = term,
            startDate = start,
            status = status,
            confidence = confidence,
            isConfirmed = confirmed,
            paidCount = paid,
            settledEarly = settledEarly,
            paid = Money(per * paid, currency),
            remainingCount = remaining,
            outstanding = Money(per * remaining, currency),
            perInstallment = Money(per, currency),
        )
    }

    private val plans = listOf(
        plan("iphone", "Apple Store", -1_079_900, 12, 4, "2026-07-18"),
        plan("dyson", "Dyson", -479_400, 6, 2, "2026-09-05", account = "amex_platinum", confidence = 0.82, confirmed = false),
    )

    private val allPlans = plans + listOf(
        plan("flight", "Cathay Pacific", -86_400, 3, 1, "2026-10-09", account = "amex_platinum", currency = "USD"),
        plan("sofa", "IKEA", -1_200_000, 12, 7, "2025-11-20", status = "completed", settledEarly = true),
    )

    private fun recurringState(inactiveExpanded: Boolean = false): RecurringUiState {
        val commitments = Recurring.commitments(charges, today)
        return RecurringUiState(
            loading = false,
            currency = "HKD",
            commitments = commitments,
            plans = plans,
            view = Recurring.view(commitments, plans, RecurringFilter(), "HKD"),
            inactiveExpanded = inactiveExpanded,
        )
    }

    @Composable
    private fun Recurring(state: RecurringUiState) = RecurringScreen(
        state = state,
        onBack = {},
        onRefresh = {},
        onSearch = {},
        onKind = {},
        onCadence = {},
        onToggleInactive = {},
        onOpenCommitment = {},
        onOpenPlan = {},
        onOpenPlans = {},
    )

    @Test
    fun recurring() = capture("recurring") { Recurring(recurringState()) }

    @Test
    fun recurringDark() = capture("recurring_dark", dark = true) {
        // Fewer active subscriptions, so the inactive list and the plans are on screen.
        val commitments = Recurring.commitments(charges, today).filter { it.key in setOf("netflix", "disney+", "headspace") }
        Recurring(
            RecurringUiState(
                loading = false,
                commitments = commitments,
                plans = plans,
                view = Recurring.view(commitments, plans, RecurringFilter(), "HKD"),
                inactiveExpanded = true,
            ),
        )
    }

    @Test
    fun recurringFiltered() = capture("recurring_filtered") {
        val base = recurringState()
        val filter = RecurringFilter(search = "zzz")
        Recurring(base.copy(filter = filter, view = Recurring.view(base.commitments, base.plans, filter, "HKD")))
    }

    @Test
    fun recurringEmpty() = capture("recurring_empty") { Recurring(RecurringUiState(loading = false)) }

    @Test
    fun recurringDetail() = capture("recurring_detail") {
        val netflix = Recurring.commitments(charges, today).first { it.key == "netflix" }
        RecurringDetailScreen(
            state = RecurringDetailUiState(key = "netflix", loading = false, commitment = netflix),
            onBack = {},
            onRefresh = {},
            onOpenInBlotter = {},
            onOpenCharge = {},
        )
    }

    @Test
    fun recurringDetailInactive() = capture("recurring_detail_inactive", dark = true) {
        val disney = Recurring.commitments(charges, today).first { it.key == "disney+" }
        RecurringDetailScreen(
            state = RecurringDetailUiState(key = "disney+", loading = false, commitment = disney),
            onBack = {},
            onRefresh = {},
            onOpenInBlotter = {},
            onOpenCharge = {},
        )
    }

    private val instalments = InstallmentsUiState(
        loading = false,
        response = InstallmentsResponse(
            plans = allPlans,
            outstandingByCurrency = listOf(CurrencyAmount("HKD", -1_039_532), CurrencyAmount("USD", -57_600)),
            committedMonthlyByCurrency = listOf(CurrencyAmount("HKD", -169_891), CurrencyAmount("USD", -28_800)),
        ),
        activeOnly = false,
    )

    @Test
    fun instalments() = capture("instalments") {
        InstallmentsScreen(state = instalments, onBack = {}, onRefresh = {}, onActiveOnly = {}, onOpenPlan = {})
    }

    @Test
    fun instalmentsEmpty() = capture("instalments_empty") {
        InstallmentsScreen(
            state = InstallmentsUiState(loading = false, response = InstallmentsResponse()),
            onBack = {},
            onRefresh = {},
            onActiveOnly = {},
            onOpenPlan = {},
        )
    }

    @Test
    fun instalmentDetail() = capture("instalment_detail") {
        val plan = plans.first().copy(
            charges = listOf(
                InstallmentCharge("c2", "2026-08-18", "APPLE STORE HK 02/12", -89_991, "HKD", installmentSeq = 2),
                InstallmentCharge("c3", "2026-09-18", "APPLE STORE HK 03/12", -89_991, "HKD", installmentSeq = 3),
                InstallmentCharge("c4", "2026-10-18", "APPLE STORE HK 04/12", -89_991, "HKD", installmentSeq = 4),
            ),
        )
        InstallmentDetailScreen(
            state = InstallmentDetailUiState(loading = false, plan = plan, schedule = InstallmentSchedule.of(plan)),
            onBack = {},
            onRefresh = {},
            onOpenCharge = { _, _ -> },
        )
    }

    @Test
    fun instalmentDetailSettled() = capture("instalment_detail_settled", dark = true) {
        val plan = plan("tv", "Fortress", -1_200_000, 6, 3, "2026-02-14", status = "completed", settledEarly = true).copy(
            paid = hkd(-1_200_000),
            charges = (1..3).map { seq ->
                InstallmentCharge("s$seq", LocalDate.parse("2026-02-14").plusMonths(seq - 1L).toString(), "FORTRESS HK $seq/6", -200_000, "HKD", installmentSeq = seq)
            } + InstallmentCharge("settle", "2026-05-02", "FORTRESS HK EARLY SETTLEMENT", -600_000, "HKD", isSettlement = true),
        )
        InstallmentDetailScreen(
            state = InstallmentDetailUiState(loading = false, plan = plan, schedule = InstallmentSchedule.of(plan)),
            onBack = {},
            onRefresh = {},
            onOpenCharge = { _, _ -> },
        )
    }
}
