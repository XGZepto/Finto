package page.zepto.finto

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.model.Holding
import page.zepto.finto.core.model.InvestmentActivity
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentSnapshot
import page.zepto.finto.core.model.InvestmentSubaccount
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MpfActivityCount
import page.zepto.finto.core.model.MpfActivityResult
import page.zepto.finto.core.model.MpfBundlePreview
import page.zepto.finto.core.model.MpfDocument
import page.zepto.finto.core.model.MpfImportResult
import page.zepto.finto.core.model.MpfSnapshotPreview
import page.zepto.finto.core.model.MpfSnapshotStatus
import page.zepto.finto.core.model.ValuePoint
import page.zepto.finto.feature.investments.InvestmentsScreen
import page.zepto.finto.feature.investments.InvestmentsUiState
import page.zepto.finto.feature.investments.MpfImportScreen
import page.zepto.finto.feature.investments.MpfImportUiState
import page.zepto.finto.feature.investments.MpfStep
import page.zepto.finto.ui.TopLevelDestination

/** MPF valuations and the bundle import, from fixed invented data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class InvestmentsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        compose.captureScreen(name, dark, TopLevelDestination.More, content = content)

    private fun hkd(amount: Long) = Money(amount, "HKD")

    private val names = mapOf(
        "hsbc_mpf_employee" to "MPF employee",
        "hsbc_mpf_tvc" to "MPF tax-deductible",
        "hsbc_mpf_personal" to "MPF personal account",
    )

    private val snapshots = listOf(
        InvestmentSnapshot("s6", "2026-09-30", "hsbc_mpf", hkd(68_452_310)),
        InvestmentSnapshot("s5", "2026-06-30", "hsbc_mpf", hkd(66_105_920)),
        InvestmentSnapshot("s4", "2026-03-31", "hsbc_mpf", hkd(63_877_400)),
    )

    private val detail = InvestmentDetail(
        id = "s6",
        asOfDate = "2026-09-30",
        scheme = "hsbc_mpf",
        total = hkd(68_452_310),
        subaccounts = listOf(
            InvestmentSubaccount("hsbc_mpf_employee", "1024-5531-01", hkd(41_208_330), "0.6020"),
            InvestmentSubaccount("hsbc_mpf_tvc", "1024-5531-02", hkd(17_995_180), "0.2629"),
            InvestmentSubaccount("hsbc_mpf_personal", "1024-5531-03", hkd(9_248_800), "0.1351"),
        ),
        holdings = listOf(
            Holding("HSBC MPF North American Equity Fund", "10842.3318", "21.87", hkd(23_712_800), "0.3464"),
            Holding("HSBC MPF Global Equity Fund", "8930.0145", "19.42", hkd(17_342_100), "0.2533"),
            Holding("HSBC MPF Hong Kong and Chinese Equity Fund", "21455.9001", "5.98", hkd(12_830_630), "0.1874"),
            Holding("HSBC MPF Core Accumulation Fund", "4125.0000", "21.40", hkd(8_827_500), "0.1290"),
            Holding("HSBC MPF Conservative Fund", "4880.2210", "11.32", hkd(5_739_280), "0.0839"),
        ),
    )

    private val history = listOf(
        ValuePoint("2025-03-31", hkd(55_210_400)),
        ValuePoint("2025-06-30", hkd(57_880_150)),
        ValuePoint("2025-09-30", hkd(60_412_900)),
        ValuePoint("2025-12-31", hkd(59_330_020)),
        ValuePoint("2026-03-31", hkd(63_877_400)),
        ValuePoint("2026-06-30", hkd(66_105_920)),
        ValuePoint("2026-09-30", hkd(68_452_310)),
    )

    private val activities = listOf(
        InvestmentActivity("a1", "hsbc_mpf_employee", "1024-5531-01", "2026-09-28", "employee_mandatory", "regular_contribution", hkd(150_000)),
        InvestmentActivity("a2", "hsbc_mpf_employee", "1024-5531-01", "2026-09-28", "employer_mandatory", "regular_contribution", hkd(150_000)),
        InvestmentActivity("a3", "hsbc_mpf_tvc", "1024-5531-02", "2026-09-15", "tvc", "regular_contribution", hkd(500_000)),
        InvestmentActivity("a4", "hsbc_mpf_personal", "1024-5531-03", "2026-07-02", "personal", "transfer_in", hkd(2_400_000)),
        InvestmentActivity("a5", "hsbc_mpf_tvc", "1024-5531-02", "2026-06-30", "fee_rebate", "rebate", hkd(1_250)),
    )

    private val overview = InvestmentsUiState(
        loading = false,
        snapshots = snapshots,
        scheme = "hsbc_mpf",
        current = detail,
        history = history,
        activities = activities,
        accountNames = names,
    )

    @Composable
    private fun Overview(state: InvestmentsUiState) = InvestmentsScreen(
        state = state,
        onBack = {},
        onRefresh = {},
        onImport = {},
        onSelectScheme = {},
        onSelectSnapshot = {},
        onSelectAccount = {},
    )

    @Test
    fun investments() = capture("investments") { Overview(overview) }

    @Test
    fun investmentsDark() = capture("investments_dark", dark = true) { Overview(overview) }

    @Test
    fun investmentsAccount() = capture("investments_account") {
        Overview(
            overview.copy(
                accountId = "hsbc_mpf_tvc",
                history = listOf(
                    ValuePoint("2026-03-31", hkd(16_802_000)),
                    ValuePoint("2026-06-30", hkd(18_210_400)),
                    ValuePoint("2026-09-30", hkd(17_995_180)),
                ),
                activities = activities.filter { it.accountId == "hsbc_mpf_tvc" },
            ),
        )
    }

    @Test
    fun investmentsEmpty() = capture("investments_empty") { Overview(InvestmentsUiState(loading = false)) }

    private val files = (1..5).map { StagedFile("statement-$it.pdf", 120_000, "application/pdf", "/tmp/statement-$it.pdf") }

    private val preview = MpfBundlePreview(
        bundleSha256 = "9f2c",
        documents = listOf(
            MpfDocument("00-HSBC_MPF_Member_Returns_2026Q3.pdf", "member_returns", "deterministic"),
            MpfDocument("01-Account_Returns_Employee_2026Q3.pdf", "account_returns", "deterministic"),
            MpfDocument("02-Account_Returns_TVC_2026Q3.pdf", "account_returns", "deterministic"),
            MpfDocument("03-Account_Returns_Personal_2026Q3.pdf", "account_returns", "llm", "claude-sonnet-4-5"),
            MpfDocument("04-Contribution_History_2026.pdf", "contribution_history", "deterministic"),
        ),
        snapshot = MpfSnapshotPreview(
            asOfDate = "2026-09-30",
            reportedDate = "2026-10-08",
            total = hkd(68_452_310),
            subaccounts = detail.subaccounts.map { it.copy(allocation = null) },
            holdings = 5,
        ),
        activities = MpfActivityCount(
            count = 42,
            byAccount = mapOf("hsbc_mpf_employee" to 24, "hsbc_mpf_tvc" to 12, "hsbc_mpf_personal" to 6),
        ),
    )

    @Composable
    private fun Import(step: MpfStep) = MpfImportScreen(
        state = MpfImportUiState(step = step, accountNames = names),
        onBack = {},
        onPick = {},
        onConfirm = {},
        onReset = {},
        onDone = {},
    )

    @Test
    fun mpfChooser() = capture("mpf_import") { Import(MpfStep.Idle) }

    @Test
    fun mpfPreview() = capture("mpf_preview") { Import(MpfStep.Ready(files, preview)) }

    @Test
    fun mpfPreviewRejected() = capture("mpf_preview_rejected", dark = true) {
        Import(MpfStep.Ready(files, preview, error = "MPF bundle does not match the preview SHA-256"))
    }

    @Test
    fun mpfResult() = capture("mpf_result") {
        Import(
            MpfStep.Done(
                preview,
                MpfImportResult(bundleSha256 = "9f2c", snapshot = MpfSnapshotStatus("imported", "s7"), activities = MpfActivityResult(inserted = 38, skipped = 4)),
            ),
        )
    }

    @Test
    fun mpfFailed() = capture("mpf_failed") {
        Import(MpfStep.Failed("Member returns statement is missing from the bundle"))
    }
}
