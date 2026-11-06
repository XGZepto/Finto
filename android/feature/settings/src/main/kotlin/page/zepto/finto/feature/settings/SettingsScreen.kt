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
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.designsystem.theme.tr
import page.zepto.finto.core.model.Translations
import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    appVersion: String,
    onBack: () -> Unit,
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
        onBack = onBack,
        onLanguageChange = viewModel::setLanguage,
        onCreateKey = viewModel::createApiKey,
        onRevokeKey = viewModel::revokeApiKey,
        onDismissKey = viewModel::dismissNewKey,
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
    onBack: (() -> Unit)? = null,
    onLanguageChange: (String) -> Unit = {},
    onCreateKey: () -> Unit = {},
    onRevokeKey: (String) -> Unit = {},
    onDismissKey: () -> Unit = {},
) {
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    var pickLanguage by rememberSaveable { mutableStateOf(false) }
    var revoking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { FintoTopBar(tr("Settings"), onBack = onBack) }) { padding ->
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

            SectionHeader(tr("Preferences"))
            Text(
                tr("Appearance"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(tr(mode.name)) },
                    )
                }
            }
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { pickLanguage = true },
                leadingContent = { Icon(Icons.Outlined.Translate, null) },
                headlineContent = { Text(tr("Language")) },
                trailingContent = { Text(Translations.languages.firstOrNull { it.first == state.language }?.second ?: "English") },
            )
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { pickCurrency = true },
                leadingContent = { Icon(Icons.Outlined.CurrencyExchange, null) },
                headlineContent = { Text(tr("Reporting currency")) },
                supportingContent = state.error?.let { error -> { Text(error, color = MaterialTheme.colorScheme.error) } },
                trailingContent = { Text(if (state.savingCurrency) "Saving…" else state.currency) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Switch) { onDynamicColorChange(!state.dynamicColor) },
                    leadingContent = { Icon(Icons.Outlined.Palette, null) },
                    headlineContent = { Text("Dynamic colour") },
                    supportingContent = { Text("Use wallpaper colours") },
                    trailingContent = { Switch(checked = state.dynamicColor, onCheckedChange = null) },
                )
            }

            SectionHeader("API access", trailing = { TextButton(onClick = onCreateKey) { Text("Generate key") } })
            state.newKey?.let { key -> NewKeyCard(key, onDismissKey) }
            state.apiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            when {
                state.apiKeysLoading -> Unit
                state.apiKeys.isEmpty() -> Text(
                    "No API keys",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                else -> state.apiKeys.forEach { key ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Key, null) },
                        headlineContent = { Text(key.name) },
                        supportingContent = {
                            Text(
                                "${key.prefix}… · " + (key.lastUsedAt?.let { "used ${it.take(10)}" } ?: "unused"),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { revoking = key.id }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                        },
                    )
                }
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
                headlineContent = { Text(tr("Sign out"), color = MaterialTheme.colorScheme.error) },
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
    if (pickLanguage) {
        AlertDialog(
            onDismissRequest = { pickLanguage = false },
            title = { Text(tr("Language")) },
            text = {
                Column {
                    Translations.languages.forEach { (code, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = code == state.language, role = Role.RadioButton) {
                                    onLanguageChange(code)
                                    pickLanguage = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == state.language, onClick = null)
                            Text(name, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Close") } },
        )
    }
    revoking?.let { id ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke this key?") },
            text = { Text("Anything using it stops working.") },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    onRevokeKey(id)
                }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("${tr("Sign out")}?") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(tr("Sign out")) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** The new key, shown once with a copy button; the clipboard entry is marked sensitive. */
@Composable
private fun NewKeyCard(key: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(key) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Copy this key now. It won't be shown again.", style = MaterialTheme.typography.bodySmall)
            Text(key, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val clip = ClipData.newPlainText("Finto API key", key).apply {
                            // ClipDescription.EXTRA_IS_SENSITIVE (API 33): keeps the key out of the clipboard preview.
                            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        }
                        clipboard.setClipEntry(ClipEntry(clip))
                        copied = true
                    }
                }) { Text(if (copied) "Copied" else "Copy") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
