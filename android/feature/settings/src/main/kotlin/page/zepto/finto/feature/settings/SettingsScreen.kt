package page.zepto.finto.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.data.repository.ThemeMode
import page.zepto.finto.core.designsystem.component.SectionHeader

@Composable
fun SettingsRoute(
    appVersion: String,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        appVersion = appVersion,
        onCurrencyChange = viewModel::setCurrency,
        onThemeModeChange = viewModel::setThemeMode,
        onDynamicColorChange = viewModel::setDynamicColor,
        onSignOut = viewModel::signOut,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onCurrencyChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.username.take(1).uppercase().ifEmpty { "F" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column {
                    Text(state.username, style = MaterialTheme.typography.titleMedium)
                    Text(state.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionHeader("Reporting")
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { pickCurrency = true },
                leadingContent = { Icon(Icons.Outlined.CurrencyExchange, null) },
                headlineContent = { Text("Reporting currency") },
                supportingContent = state.error?.let { error -> { Text(error, color = MaterialTheme.colorScheme.error) } },
                trailingContent = { Text(if (state.savingCurrency) "Saving…" else state.currency) },
            )

            SectionHeader("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(mode.name) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Switch) { onDynamicColorChange(!state.dynamicColor) },
                    leadingContent = { Icon(Icons.Outlined.Palette, null) },
                    headlineContent = { Text("Dynamic colour") },
                    supportingContent = { Text("Use wallpaper colours") },
                    trailingContent = { Switch(checked = state.dynamicColor, onCheckedChange = null) },
                )
            }

            SectionHeader("About")
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri("https://${state.server}") },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                headlineContent = { Text("Open Finto on the web") },
            )
            ListItem(headlineContent = { Text("Version") }, trailingContent = { Text(appVersion) })
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { confirmSignOut = true },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
            )
        }
    }

    if (pickCurrency) {
        AlertDialog(
            onDismissRequest = { pickCurrency = false },
            title = { Text("Reporting currency") },
            text = {
                Column {
                    SettingsViewModel.CURRENCIES.forEach { currency ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = currency == state.currency, role = Role.RadioButton) {
                                    onCurrencyChange(currency)
                                    pickCurrency = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currency == state.currency, onClick = null)
                            Text(currency, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCurrency = false }) { Text("Close") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
