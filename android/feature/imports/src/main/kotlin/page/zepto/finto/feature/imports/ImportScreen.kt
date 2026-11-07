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
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.ListSkeleton
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.ImportedFile
import page.zepto.finto.core.model.Labels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.OpenLedger
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
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenTransaction: (LedgerFilter, String?, String) -> Unit = { _, _, _ -> },
    onOpenAccount: (String) -> Unit = {},
    onOpenMatching: () -> Unit = {},
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
        onOpenActivity = {
            val done = state.step as? ImportStep.Done
            val file = done?.result?.statementFileId
            onOpenLedger(
                if (file != null) LedgerFilter(statements = listOf(file), includeTransfers = true) else LedgerFilter(),
                if (file != null) "Imported rows" else null,
            )
        },
        onBack = onBack,
        actions = ImportActions(
            onOpenRows = { statement, uncategorised ->
                onOpenLedger(LedgerFilter(statements = listOf(statement), includeTransfers = true, uncategorisedOnly = uncategorised), if (uncategorised) "Uncategorised" else "Imported rows")
            },
            onOpenTransaction = { statement, id -> onOpenTransaction(LedgerFilter(statements = listOf(statement), includeTransfers = true), "Imported rows", id) },
            onOpenAccount = onOpenAccount,
            onOpenMatching = onOpenMatching,
            onToggleHistory = viewModel::toggleHistoryReview,
            onRerunHistoryReview = viewModel::rerunHistoryReview,
            onRetryReview = viewModel::runReview,
            onMaintenance = viewModel::runMaintenance,
            account = AccountActions(
                onChoose = viewModel::chooseAccount,
                onNew = viewModel::startNewAccount,
                onDraftChange = viewModel::updateDraft,
                onCreate = viewModel::createAccount,
                onCancelDraft = viewModel::cancelDraft,
            ),
        ),
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
    onBack: (() -> Unit)? = null,
    actions: ImportActions = ImportActions(),
) {
    Scaffold(topBar = { FintoTopBar("Import", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val step = state.step) {
                ImportStep.Idle -> IdleContent(state, onPick, actions)
                is ImportStep.Reading -> Progress("Reading ${step.name ?: "the file"}…")
                is ImportStep.Previewing -> Progress("Checking ${step.file.name} against your ledger…")
                is ImportStep.Ready -> PreviewContent(step.preview, state, importing = false, onConfirm, onReset, actions.account)
                is ImportStep.Importing -> PreviewContent(step.preview, state, importing = true, onConfirm, onReset, actions.account)
                is ImportStep.Done -> DoneContent(step, state.review, onOpenActivity, onReset, actions)
                is ImportStep.Failed -> FailedContent(step, onPick, onReset)
            }
        }
    }
}

@Composable
private fun IdleContent(state: ImportUiState, onPick: () -> Unit, actions: ImportActions) {
    var showFormats by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item("pick") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
        item("history-header") { SectionHeader("Imported files") }
        if (state.historyLoading && state.history.isEmpty()) {
            item("history-loading") { ListSkeleton(rows = 3) }
        } else if (state.history.isEmpty()) {
            item("history-empty") {
                Text(
                    "Nothing imported yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        state.history.forEach { file ->
            item("file-${file.id}") {
                HistoryRow(file, expanded = state.historyReviewId == file.id, onClick = { actions.onToggleHistory(file) }, modifier = Modifier.animateItem())
            }
            if (state.historyReviewId == file.id) {
                item("file-review-${file.id}") {
                    Column(Modifier.animateItem().padding(bottom = 8.dp)) {
                        ReviewLoadPanel(
                            state.historyReview,
                            actions,
                            onRetry = actions.onRerunHistoryReview,
                            trailing = (state.historyReview as? ReviewLoad.Done)?.let { { TextButton(onClick = actions.onRerunHistoryReview) { Text("Run again") } } },
                        )
                    }
                }
            }
        }
        item("maintenance-header") { SectionHeader("Maintenance") }
        item("maintenance") { MaintenancePanel(state.maintenance, actions.onMaintenance) }
        if (state.formats.isNotEmpty()) {
            item("formats-header") {
                SectionHeader(
                    "Statement formats",
                    trailing = { TextButton(onClick = { showFormats = !showFormats }) { Text(if (showFormats) "Hide" else "Show ${state.formats.size}") } },
                )
            }
            if (showFormats) {
                items(state.formats.sortedBy { it.label.lowercase() }, key = { "format-${it.id}" }) { format ->
                    ListItem(
                        modifier = Modifier.animateItem(),
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
}

@Composable
private fun HistoryRow(file: ImportedFile, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = file.reviewStatus
    ListItem(
        modifier = modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Hide review" else "Show review", onClick = onClick),
        headlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    DisplayDates.short(file.importedAt.take(10)),
                    file.accountId?.let(Labels::humanize),
                    "${file.txnCount} txns",
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            val (container, content) = when (status) {
                "clean" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                "attention" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                ImportLabels.reviewStatus(status),
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(container).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
    )
}

@Composable
private fun MaintenancePanel(state: MaintenanceState?, onRun: (Maintenance) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Maintenance.entries.forEach { job ->
            val running = state?.job == job && state.running
            OutlinedButton(onClick = { onRun(job) }, enabled = state?.running != true, modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(job.label)
            }
        }
        if (state != null && !state.running) {
            when {
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                state.changes.isEmpty() -> Text("${state.job.label}: nothing changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column {
                    Text(state.job.label, style = MaterialTheme.typography.labelLarge)
                    state.changes.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.bodySmall.tabular())
                        }
                    }
                }
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
private fun PreviewContent(
    preview: StagePreview,
    state: ImportUiState,
    importing: Boolean,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    accountActions: AccountActions,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        AccountPickerSheet(
            accounts = AccountDrafts.ordered(state.accounts, preview.institutionHint),
            institutions = state.institutions,
            selected = state.chosenAccount,
            onSelect = { id -> picking = false; accountActions.onChoose(id) },
            onNew = { picking = false; accountActions.onNew() },
            onDismiss = { picking = false },
        )
    }
    state.draft?.let { draft ->
        NewAccountSheet(
            draft = draft,
            institutions = state.institutions,
            currencies = (preview.totals.keys + state.accounts.map { it.primaryCurrency }).distinct(),
            creating = state.creatingAccount,
            error = state.accountError,
            actions = accountActions,
        )
    }
    val blocked = preview.needsAccount && state.chosenAccount == null
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
        if (preview.error == null && (preview.txnCount ?: 0) > 0) {
            item("account") {
                AccountBinding(
                    preview,
                    state,
                    onOpenPicker = { if (!importing) picking = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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
                    enabled = !importing && preview.error == null && !blocked && !state.rechecking,
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
private fun DoneContent(step: ImportStep.Done, review: ReviewLoad, onOpenActivity: () -> Unit, onReset: () -> Unit, actions: ImportActions) {
    val result = step.result
    val imported = result.status == "imported"
    val changes = ImportLabels.changes(step.reconcile)
    LazyColumn(contentPadding = PaddingValues(vertical = 24.dp)) {
        item("result") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onReset) { Text("Import another") }
                    Button(onClick = onOpenActivity) { Text("Open rows") }
                }
            }
        }
        if (changes.isNotEmpty()) {
            item("reconcile-header") { SectionHeader("Reconcile") }
            item("reconcile") {
                StatGrid {
                    changes.forEach { change ->
                        StatTile(
                            change.label,
                            count(change.value),
                            Modifier.weight(1f),
                            valueColor = if (change.notable) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Unspecified,
                            onClick = if (change.notable) actions.onOpenMatching else null,
                        )
                    }
                }
            }
        }
        if (review != ReviewLoad.Idle) {
            item("review-header") { SectionHeader("Review") }
            item("review") {
                val statement = result.statementFileId
                ReviewLoadPanel(review, actions, onRetry = { statement?.let(actions.onRetryReview) })
            }
        }
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
