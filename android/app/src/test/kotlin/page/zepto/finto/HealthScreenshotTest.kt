package page.zepto.finto

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.CandidateRow
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.IntegritySummary
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue
import page.zepto.finto.core.model.UnverifiedAccount
import page.zepto.finto.core.model.Violation
import page.zepto.finto.feature.health.IntegrityScreen
import page.zepto.finto.feature.health.IntegrityUiState
import page.zepto.finto.feature.health.QueueState
import page.zepto.finto.feature.health.ReviewScreen
import page.zepto.finto.feature.health.ReviewUiState
import page.zepto.finto.ui.TopLevelDestination

/** Matching suggestions and the integrity report, from invented data shaped like the demo ledger. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HealthScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        compose.captureScreen(name, dark, TopLevelDestination.More, content)

    @Test
    fun reviewDuplicates() = capture("review-duplicates") { Review(HealthSample.review(ReviewQueue.Duplicates)) }

    @Test
    fun reviewDuplicatesDark() = capture("review-duplicates-dark", dark = true) { Review(HealthSample.review(ReviewQueue.Duplicates)) }

    @Test
    fun reviewTransfers() = capture("review-transfers") { Review(HealthSample.review(ReviewQueue.Transfers, cursor = 1)) }

    @Test
    fun reviewInstalments() = capture("review-instalments") { Review(HealthSample.review(ReviewQueue.Installments)) }

    @Test
    fun reviewEmpty() = capture("review-empty") {
        Review(ReviewUiState(queue = ReviewQueue.Duplicates, queues = ReviewQueue.entries.associateWith { QueueState(loaded = true) }))
    }

    @Test
    fun reviewLoading() = capture("review-loading") { Review(ReviewUiState()) }

    @Test
    fun integrityHealthy() = capture("integrity-healthy") { Integrity(IntegrityUiState(loading = false, report = HealthSample.healthy, showAll = true)) }

    @Test
    fun integrityProblems() = capture("integrity-problems") { Integrity(IntegrityUiState(loading = false, report = HealthSample.problems)) }

    /** The whole report on one tall screen, down to the unverified accounts. */
    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun integrityProblemsFull() = capture("integrity-problems-full") { Integrity(IntegrityUiState(loading = false, report = HealthSample.problems)) }

    @Test
    fun integrityProblemsDark() = capture("integrity-problems-dark", dark = true) {
        Integrity(IntegrityUiState(loading = false, report = HealthSample.problems, showAll = true))
    }

    @Composable
    private fun Review(state: ReviewUiState) = ReviewScreen(
        state = state,
        onBack = {}, onSelect = {}, onMove = {}, onResolve = {}, onOpenRow = { _, _, _ -> },
        onRefresh = {}, onRetry = {}, onMessageShown = {},
    )

    @Composable
    private fun Integrity(state: IntegrityUiState) = IntegrityScreen(
        state = state,
        onBack = {}, onRerun = {}, onShowAll = {}, onInspect = {}, onOpenUnverified = {}, onOpenAccount = {}, onMessageShown = {},
    )
}

private object HealthSample {
    private fun hkd(amount: Long) = Money(amount, "HKD")

    private val names = mapOf(
        "amex_hk_main" to "AMEX Platinum",
        "hsbc_hk_current" to "HSBC Current",
        "hsbc_hk_savings" to "HSBC Savings",
        "mox_hk" to "Mox",
    )

    private val duplicates = listOf(
        ReviewCandidate(
            id = "dup-1", score = 0.93,
            reasons = listOf("same amount", "same account", "dates 1 day apart", "descriptions 91% alike"),
            keepId = "t-101", keepDate = "2026-10-14", keepAccount = "amex_hk_main", keepDesc = "CITYSUPER IFC MALL HONG KONG",
            keepAmount = -128_040, keepCurrency = "HKD",
            dupeId = "t-388", dupeDate = "2026-10-15", dupeAccount = "amex_hk_main", dupeDesc = "CITYSUPER IFC HK",
            dupeAmount = -128_040, dupeCurrency = "HKD",
        ),
        ReviewCandidate(
            id = "dup-2", score = 0.74, reasons = listOf("same amount", "same date"),
            keepId = "t-102", keepDate = "2026-10-02", keepAccount = "mox_hk", keepDesc = "MTR TRAVEL",
            keepAmount = -1_240, keepCurrency = "HKD",
            dupeId = "t-389", dupeDate = "2026-10-02", dupeAccount = "mox_hk", dupeDesc = "MTR TRAVEL",
            dupeAmount = -1_240, dupeCurrency = "HKD",
        ),
        ReviewCandidate(
            id = "dup-3", score = 0.61,
            keepId = "t-103", keepDate = "2026-09-28", keepAccount = "amex_hk_main", keepDesc = "APPLE.COM/BILL",
            keepAmount = -7_800, keepCurrency = "HKD",
            dupeId = "t-390", dupeDate = "2026-09-30", dupeAccount = "amex_hk_main", dupeDesc = "APPLE.COM/BILL ITUNES.COM",
            dupeAmount = -7_800, dupeCurrency = "HKD",
        ),
    )

    private val transfers = listOf(
        ReviewCandidate(
            id = "tr-1", score = 0.88, reasons = listOf("amounts match", "opposite signs"), dateDelta = 0, amountDelta = 0,
            outId = "t-201", outDate = "2026-10-01", outAccount = "hsbc_hk_current", outDesc = "TRANSFER TO 012-345678-833",
            outAmount = -2_000_000, outCurrency = "HKD",
            inId = "t-202", inDate = "2026-10-01", inAccount = "hsbc_hk_savings", inDesc = "TRANSFER FROM 012-345678-001",
            inAmount = 2_000_000, inCurrency = "HKD",
        ),
        ReviewCandidate(
            id = "tr-2", score = 0.78,
            reasons = listOf("amounts within 0.1%", "opposite signs", "counterparty named in description"),
            dateDelta = 2, amountDelta = 1_500,
            outId = "t-203", outDate = "2026-10-03", outAccount = "hsbc_hk_current", outDesc = "FPS PAYMENT AMEX CARD 3007",
            outAmount = -2_524_395, outCurrency = "HKD",
            inId = "t-204", inDate = "2026-10-05", inAccount = "amex_hk_main", inDesc = "PAYMENT RECEIVED - THANK YOU",
            inAmount = 2_522_895, inCurrency = "HKD",
        ),
    )

    private val instalments = listOf(
        ReviewCandidate(
            id = "inst-1", score = 0.9, reasons = listOf("same amount each month", "marker 1/12 to 3/12"),
            description = "APPLE STORE CANTON RD", termMonths = 12, accountId = "amex_hk_main",
            transactions = listOf(
                CandidateRow("t-301", "2026-08-12", "APPLE STORE CANTON RD 01/12", -83_250, "HKD"),
                CandidateRow("t-302", "2026-09-12", "APPLE STORE CANTON RD 02/12", -83_250, "HKD"),
                CandidateRow("t-303", "2026-10-12", "APPLE STORE CANTON RD 03/12", -83_250, "HKD"),
            ),
        ),
    )

    fun review(queue: ReviewQueue, cursor: Int = 0) = ReviewUiState(
        queue = queue,
        queues = mapOf(
            ReviewQueue.Duplicates to QueueState(duplicates, duplicates.size, loaded = true),
            ReviewQueue.Transfers to QueueState(transfers, transfers.size, loaded = true),
            ReviewQueue.Installments to QueueState(instalments, instalments.size, loaded = true),
        ),
        cursor = cursor,
        accountNames = names,
    )

    private fun check(account: String, name: String, start: String, end: String, expected: Long, actual: Long) = BalanceCheck(
        accountId = account, accountName = name, periodStart = start, periodEnd = end,
        expectedDelta = hkd(expected), actualDelta = hkd(actual), discrepancy = hkd(actual - expected),
        status = if (actual == expected) "ok" else "discrepancy",
    )

    private val passing = listOf(
        check("hsbc_hk_current", "HSBC Current", "2026-07-01", "2026-07-31", 1_284_220, 1_284_220),
        check("hsbc_hk_current", "HSBC Current", "2026-08-01", "2026-08-31", -412_960, -412_960),
        check("hsbc_hk_current", "HSBC Current", "2026-09-01", "2026-09-30", 905_115, 905_115),
        check("amex_hk_main", "AMEX Platinum", "2026-08-05", "2026-09-04", -2_522_895, -2_522_895),
        check("amex_hk_main", "AMEX Platinum", "2026-09-05", "2026-10-04", -1_987_430, -1_987_430),
        check("hsbc_hk_savings", "HSBC Savings", "2026-07-01", "2026-09-30", 2_003_412, 2_003_412),
    )

    val healthy = IntegrityReport(
        healthy = true,
        balanceChecks = passing + BalanceCheck(accountId = "mox_hk", accountName = "Mox", status = "insufficient_data"),
        unverifiedAccounts = listOf(UnverifiedAccount("mox_hk", "Mox", 214)),
        summary = IntegritySummary(checksRun = 7, discrepancyCount = 0, violationCount = 0, unverifiedAccountCount = 1),
    )

    val problems = IntegrityReport(
        healthy = false,
        violations = listOf(
            Violation("one_legged_transfer", 2, "transfer group with fewer than two legs"),
        ),
        balanceChecks = listOf(
            check("amex_hk_main", "AMEX Platinum", "2026-10-05", "2026-11-04", -2_310_560, -2_262_560),
            check("hsbc_hk_current", "HSBC Current", "2026-10-01", "2026-10-31", 1_048_300, 1_036_050),
        ) + passing.take(4),
        unverifiedAccounts = listOf(
            UnverifiedAccount("mox_hk", "Mox", 214),
            UnverifiedAccount("wise_usd", "Wise USD", 1_088),
        ),
        summary = IntegritySummary(checksRun = 6, discrepancyCount = 2, violationCount = 2, unverifiedAccountCount = 2),
    )
}
