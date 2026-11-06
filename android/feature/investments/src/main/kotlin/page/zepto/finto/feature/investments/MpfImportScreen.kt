package page.zepto.finto.feature.investments

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.MpfBundlePreview
import page.zepto.finto.core.model.MpfDocument
import page.zepto.finto.core.model.MpfImportResult

@Composable
fun MpfImportRoute(
    onBack: () -> Unit,
    onImported: () -> Unit,
    viewModel: MpfImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onFilesChosen(uris)
    }
    MpfImportScreen(
        state = state,
        onBack = onBack,
        onPick = { picker.launch(arrayOf("application/pdf")) },
        onConfirm = viewModel::confirm,
        onReset = viewModel::reset,
        onDone = onImported,
    )
}

@Composable
fun MpfImportScreen(
    state: MpfImportUiState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val step = state.step
    Scaffold(
        topBar = { FintoTopBar("Import MPF bundle", onBack = onBack) },
        bottomBar = {
            when (step) {
                is MpfStep.Ready -> ActionBar(onConfirm, onPick, busy = false)
                is MpfStep.Importing -> ActionBar(onConfirm, onPick, busy = true)
                else -> Unit
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = { crossfade() },
            label = "mpf-step",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { shown ->
            when (shown) {
                MpfStep.Idle -> Chooser(onPick)
                is MpfStep.Validating -> Progress("Validating ${plural(shown.count, "PDF")}…")
                is MpfStep.Ready -> PreviewContent(state, shown.preview, shown.error)
                is MpfStep.Importing -> PreviewContent(state, shown.preview, error = null)
                is MpfStep.Done -> DoneContent(shown.preview, shown.result, onDone, onReset)
                is MpfStep.Failed -> MessageState(
                    Icons.Outlined.ErrorOutline,
                    "Couldn't read this bundle",
                    shown.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    action = { Button(onClick = onPick) { Text("Choose PDFs") } },
                )
            }
        }
    }
}

@Composable
private fun Chooser(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("HSBC MPF statements", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Member Returns, Account Returns and Contribution History PDFs, up to ${MpfImportViewModel.MAX_FILES}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onPick) { Text("Choose PDFs") }
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ActionBar(onConfirm: () -> Unit, onPick: () -> Unit, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Other PDFs") }
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Importing…")
                } else {
                    Text("Import bundle")
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(state: MpfImportUiState, preview: MpfBundlePreview, error: String?) {
    val snapshot = preview.snapshot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (error != null) {
            item(key = "error") { ErrorBanner(error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        item(key = "total") {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(20.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total value", style = MaterialTheme.typography.titleSmall, color = onContainer)
                    MoneyText(snapshot.total, style = MaterialTheme.typography.displaySmall, color = onContainer, whole = true)
                    Text(
                        listOfNotNull(
                            "Valued ${DisplayDates.medium(snapshot.asOfDate)}",
                            snapshot.reportedDate?.takeIf { it.isNotBlank() }?.let { "reported ${DisplayDates.medium(it)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
        item(key = "counts") {
            StatGrid(Modifier.padding(top = 8.dp)) {
                val tile = Modifier.weight(1f)
                StatTile("Fund holdings", snapshot.holdings.toString(), tile)
                StatTile("Activities", preview.activities.count.toString(), tile)
            }
        }
        if (snapshot.subaccounts.isNotEmpty()) {
            item(key = "accounts-header") { SectionHeader("Member accounts", trailing = { CountText(snapshot.subaccounts.size) }) }
            items(snapshot.subaccounts, key = { "account-${it.accountId}" }) { account ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(state.accountLabel(account.accountId), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOfNotNull(
                            account.memberNo?.takeIf { it.isNotBlank() }?.let { "Member $it" },
                            preview.activities.byAccount[account.accountId]?.let { plural(it, "activity", "activities") },
                        )
                        if (details.isNotEmpty()) {
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    MoneyText(account.balance, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
        }
        item(key = "documents-header") { SectionHeader("Documents", trailing = { CountText(preview.documents.size) }) }
        items(preview.documents, key = { "doc-${it.filename}" }) { document -> DocumentRow(document) }
    }
}

@Composable
private fun CountText(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun DocumentRow(document: MpfDocument) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(document.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (document.classification.isNotBlank()) {
                    Tag(Investments.friendly(document.classification))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    parserLabel(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How a document was read: by its template, or by the model when the template did not fit. */
internal fun parserLabel(document: MpfDocument): String = when (document.parser) {
    "llm" -> listOfNotNull("AI extracted", document.model?.takeIf { it.isNotBlank() }).joinToString(" · ")
    "deterministic" -> "Template"
    else -> document.parser.orEmpty()
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun DoneContent(preview: MpfBundlePreview, result: MpfImportResult, onDone: () -> Unit, onReset: () -> Unit) {
    val imported = result.snapshot.status == "imported"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item(key = "status") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.TaskAlt,
                    contentDescription = null,
                    tint = if (imported) FintoThemeTokens.money.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(if (imported) "Snapshot imported" else "Snapshot already imported", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${MoneyFormat.formatWhole(preview.snapshot.total)} · ${DisplayDates.medium(preview.snapshot.asOfDate)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "counts") {
            StatGrid {
                val tile = Modifier.weight(1f)
                StatTile("Activities added", result.activities.inserted.toString(), tile)
                StatTile("Duplicates skipped", result.activities.skipped.toString(), tile)
            }
        }
        item(key = "actions") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("View investments") }
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Import another bundle") }
            }
        }
    }
}
