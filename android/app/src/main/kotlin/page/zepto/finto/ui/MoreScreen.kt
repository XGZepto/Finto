package page.zepto.finto.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.feature.ask.AskDestination
import page.zepto.finto.feature.health.IntegrityDestination
import page.zepto.finto.feature.health.ReviewDestination
import page.zepto.finto.feature.imports.ImportDestination
import page.zepto.finto.feature.investments.InvestmentsDestination
import page.zepto.finto.feature.recurring.InstallmentsDestination
import page.zepto.finto.feature.recurring.RecurringDestination
import page.zepto.finto.feature.reports.TimelineDestination
import page.zepto.finto.feature.settings.SettingsDestination

@Serializable
data object MoreDestination

data class MoreEntry(val label: String, val icon: ImageVector, val route: Any)

data class MoreGroup(val title: String?, val entries: List<MoreEntry>)

/** The web app's More page, grouped the same way. */
val MoreGroups = listOf(
    MoreGroup(
        "Money",
        listOf(
            MoreEntry("Recurring", Icons.Outlined.EventRepeat, RecurringDestination),
            MoreEntry("Ask", Icons.Outlined.AutoAwesome, AskDestination),
        ),
    ),
    MoreGroup(
        "Ledger",
        listOf(
            MoreEntry("Import", Icons.Outlined.UploadFile, ImportDestination),
            MoreEntry("Instalments", Icons.Outlined.CalendarMonth, InstallmentsDestination),
            MoreEntry("Investments", Icons.Outlined.ShowChart, InvestmentsDestination),
        ),
    ),
    MoreGroup(
        "Ledger health",
        listOf(
            MoreEntry("Matching suggestions", Icons.Outlined.JoinInner, ReviewDestination),
            MoreEntry("Integrity", Icons.AutoMirrored.Outlined.FactCheck, IntegrityDestination),
            MoreEntry("Timeline", Icons.AutoMirrored.Outlined.ViewList, TimelineDestination),
        ),
    ),
    MoreGroup(null, listOf(MoreEntry("Settings", Icons.Outlined.Settings, SettingsDestination))),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onOpen: (Any) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("More", scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            MoreGroups.forEach { group ->
                item(key = "header-${group.title}") {
                    if (group.title != null) SectionHeader(group.title) else androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                }
                group.entries.forEach { entry ->
                    item(key = entry.label) {
                        ListItem(
                            headlineContent = { Text(entry.label) },
                            leadingContent = { Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { onOpen(entry.route) },
                        )
                    }
                }
            }
        }
    }
}
