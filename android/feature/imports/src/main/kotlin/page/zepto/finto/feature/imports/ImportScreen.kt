package page.zepto.finto.feature.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.ImportFormat
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.StagePreview

/** The statement types Finto parses; the server decides which template applies. */
private val STATEMENT_TYPES = arrayOf(
    "application/pdf",
    "text/csv",
    "text/comma-separated-values",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

@Composable
fun ImportRoute(
    onOpenActivity: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFileChosen)
    }
    ImportScreen(
        state = state,
        onPick = { picker.launch(STATEMENT_TYPES) },
        onConfirm = viewModel::confirm,
        onReset = viewModel::reset,
        onOpenActivity = onOpenActivity,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportUiState,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Import") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val step = state.step) {
                ImportStep.Idle -> IdleContent(state.formats, onPick)
                is ImportStep.Reading -> Progress("Reading ${step.name ?: "the file"}…")
                is ImportStep.Previewing -> Progress("Checking ${step.file.name} against your ledger…")
                is ImportStep.Ready -> PreviewContent(step.preview, importing = false, onConfirm, onReset)
                is ImportStep.Importing -> PreviewContent(step.preview, importing = true, onConfirm, onReset)
                is ImportStep.Done -> DoneContent(step, onOpenActivity, onReset)
                is ImportStep.Failed -> FailedContent(step, onPick, onReset)
            }
        }
    }
}

@Composable
private fun IdleContent(formats: List<ImportFormat>, onPick: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Import a statement", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "PDF, CSV or XLSX. Nothing is saved until you confirm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onPick) { Text("Choose file") }
                }
            }
        }
        if (formats.isNotEmpty()) {
            item { SectionHeader("Supported statements") }
            items(formats.sortedBy { it.label.lowercase() }, key = { it.id }) { format ->
                ListItem(
                    headlineContent = { Text(format.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOf(format.fileFormat.uppercase(), format.institutions.joinToString()).filter { it.isNotEmpty() }.joinToString(" · "))
                    },
                    trailingContent = {
                        if (format.verified) Icon(Icons.Outlined.Verified, contentDescription = "Balance-verified", tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PreviewContent(preview: StagePreview, importing: Boolean, onConfirm: () -> Unit, onReset: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
            Text(preview.filename, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val period = listOfNotNull(preview.periodStart, preview.periodEnd).joinToString(" – ") { DisplayDates.medium(it) }
            Text(
                listOfNotNull(
                    preview.parser,
                    period.ifEmpty { null },
                    preview.txnCount?.let { "$it transactions" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
        preview.error?.let { error ->
            item { Notice(Icons.Outlined.ErrorOutline, "This file can't be imported", error, MaterialTheme.colorScheme.errorContainer, Modifier.padding(horizontal = 16.dp)) }
        }
        preview.agent?.let { agent ->
            item {
                val (icon, color) = when (agent.verdict) {
                    "import" -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.secondaryContainer
                    "hold" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.errorContainer
                    else -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiaryContainer
                }
                Notice(icon, agent.headline, agent.notes.joinToString("\n"), color, Modifier.padding(horizontal = 16.dp))
            }
        }
        if (preview.totals.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        preview.totals.forEach { (currency, totals) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$currency · ${totals.rows} rows", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                MoneyText(Money(totals.inflow, currency), signed = true, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.size(12.dp))
                                MoneyText(Money(totals.out, currency), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        preview.quality?.takeIf { it.checks.isNotEmpty() }?.let { quality ->
            item { SectionHeader("Checks") }
            items(quality.checks, key = { "check-${it.id}" }) { check ->
                val (icon, tint) = levelStyle(check.level)
                ListItem(
                    leadingContent = { Icon(icon, contentDescription = check.level, tint = tint) },
                    headlineContent = { Text(check.title) },
                    supportingContent = check.detail.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                )
            }
        }
        if (preview.warnings.isNotEmpty()) {
            item {
                Text(
                    preview.warnings.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (preview.sample.isNotEmpty()) {
            item { SectionHeader("First rows") }
            items(preview.sample.take(10)) { row ->
                ListItem(
                    headlineContent = { Text(row.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(DisplayDates.medium(row.date)) },
                    trailingContent = { MoneyText(Money(row.amount, row.currency), signed = true) },
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                OutlinedButton(onClick = onReset, enabled = !importing, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    enabled = !importing && preview.error == null,
                    modifier = Modifier.weight(1f),
                ) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(preview.txnCount?.let { "Import $it" } ?: "Import")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneContent(step: ImportStep.Done, onOpenActivity: () -> Unit, onReset: () -> Unit) {
    val result = step.result
    val imported = result.status == "imported"
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            if (imported) Icons.Outlined.TaskAlt else Icons.Outlined.Info,
            null,
            Modifier.size(48.dp),
            tint = if (imported) FintoThemeTokens.money.positive else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (imported) "Imported ${result.txns} transactions" else "Nothing new to import",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        val period = listOfNotNull(result.periodStart, result.periodEnd).joinToString(" – ") { DisplayDates.medium(it) }
        Text(
            listOfNotNull(step.preview.filename, period.ifEmpty { null }, result.reason).joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenActivity) { Text("Open activity") }
        OutlinedButton(onClick = onReset) { Text("Import another") }
    }
}

@Composable
private fun FailedContent(step: ImportStep.Failed, onPick: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Notice(Icons.Outlined.ErrorOutline, "Import didn't finish", step.message, MaterialTheme.colorScheme.errorContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReset) { Text("Start over") }
            Button(onClick = onPick) { Text("Choose another file") }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, body: String, container: Color, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun levelStyle(level: String): Pair<ImageVector, Color> = when (level) {
    "ok" -> Icons.Outlined.CheckCircle to FintoThemeTokens.money.positive
    "warn" -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
    "bad" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    else -> Icons.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
}
