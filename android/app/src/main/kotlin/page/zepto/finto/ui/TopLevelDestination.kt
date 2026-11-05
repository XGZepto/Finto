package page.zepto.finto.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector
import page.zepto.finto.feature.accounts.AccountsDestination
import page.zepto.finto.feature.imports.ImportDestination
import page.zepto.finto.feature.settings.SettingsDestination
import page.zepto.finto.feature.summary.SummaryDestination
import page.zepto.finto.feature.transactions.ActivityDestination
import kotlin.reflect.KClass

enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Summary(SummaryDestination, SummaryDestination::class, "Summary", Icons.Outlined.Insights, Icons.Filled.Insights),
    Activity(
        ActivityDestination(),
        ActivityDestination::class,
        "Activity",
        Icons.AutoMirrored.Outlined.ReceiptLong,
        Icons.AutoMirrored.Filled.ReceiptLong,
    ),
    Accounts(AccountsDestination, AccountsDestination::class, "Accounts", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    Import(ImportDestination, ImportDestination::class, "Import", Icons.Outlined.UploadFile, Icons.Filled.UploadFile),
    Settings(SettingsDestination, SettingsDestination::class, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}
