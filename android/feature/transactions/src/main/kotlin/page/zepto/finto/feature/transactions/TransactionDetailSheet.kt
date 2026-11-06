package page.zepto.finto.feature.transactions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.CategorySuggestion
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.LinkedRow
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.Txn

/** What the sheet can do; the defaults do nothing, for previews and screenshots. */
data class DetailActions(
    val onDismiss: () -> Unit = {},
    val onOpenLedger: (LedgerFilter, String?) -> Unit = { _, _ -> },
    val onOpenAccount: (String) -> Unit = {},
    val onOpenInstallment: (String) -> Unit = {},
    val onOpenLinked: (String) -> Unit = {},
    val onCategorise: () -> Unit = {},
    val onAcceptSuggestion: (CategorySuggestion) -> Unit = {},
    val onSetCategory: (String) -> Unit = {},
    val onSetMerchant: (String) -> Unit = {},
    val onSetNotes: (String) -> Unit = {},
    val onAddTag: (String) -> Unit = {},
    val onRemoveTag: (String) -> Unit = {},
    val onSetReviewState: (String) -> Unit = {},
)

@Composable
fun TransactionDetailSheet(state: DetailState, editor: EditorState, actions: DetailActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        TransactionDetail(state, editor, actions)
    }
}

/**
 * Every fact on this sheet traces to a line in a statement, and is also a way
 * into the rest of the ledger: the merchant, category, account and tags open
 * the rows that share them.
 */
@Composable
fun TransactionDetail(
    state: DetailState,
    editor: EditorState = EditorState(),
    actions: DetailActions = DetailActions(),
    modifier: Modifier = Modifier,
    startInPicker: Boolean = false,
) {
    val txn = (state as? DetailState.Loaded)?.full ?: state.summary
    var picking by rememberSaveable(txn.id) { mutableStateOf(startInPicker) }
    AnimatedContent(picking, transitionSpec = { crossfade() }, label = "detail", modifier = modifier) { showPicker ->
        if (showPicker) {
            CategoryPicker(txn, editor, actions, onDone = { picking = false })
        } else {
            DetailBody(state, txn, editor, actions, onCategorise = {
                actions.onCategorise()
                picking = true
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(state: DetailState, txn: Txn, editor: EditorState, actions: DetailActions, onCategorise: () -> Unit) {
    var editing by rememberSaveable(txn.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        // Hero: what, how much, when and where.
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(txn), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Text(txn.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (txn.merchant != null && txn.merchant != txn.description) {
                Text(txn.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DisplayDates.medium(txn.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    txn.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open account") { actions.onOpenAccount(txn.accountId) },
                )
            }
        }
        when (state) {
            is DetailState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            is DetailState.Failed -> ErrorLine(state.message)
            is DetailState.Loaded -> Unit
        }
        editor.error?.let { ErrorLine(it) }
        AnimatedVisibility(editor.saving) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) }

        if (txn.reviewState == "flagged") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Flagged for review", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onSetReviewState("confirmed") }, enabled = !editor.saving) { Text("Mark reviewed") }
                }
            }
        }

        txn.native?.takeIf { it.currency != txn.booked.currency }?.let { native ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text("Paid in ${native.currency}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        MoneyText(native, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    listOfNotNull(
                        txn.fxRate?.let { "Rate $it" },
                        txn.fxFee?.let { "FX fee ${MoneyFormat.format(it)}" },
                    ).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SheetSection("Details")
        FactRow(
            "Category",
            listOfNotNull(txn.category, txn.subcategory).joinToString(" › ") { Labels.humanize(it) }.ifEmpty { "Uncategorised" },
            action = { TextButton(onClick = onCategorise, enabled = !editor.saving) { Text(if (txn.category == null) "Categorise" else "Change") } },
        )
        FactRow("Status", "${Labels.humanize(txn.status)} · ${Labels.kind(txn.kind)}")
        txn.cardholderName?.let { holder -> FactRow("Cardholder", holder + (txn.cardLast4?.let { " · ••$it" } ?: "")) }
        txn.postedDate?.takeIf { it != txn.date }?.let { FactRow("Posted", DisplayDates.medium(it)) }
        txn.counterparty?.let { FactRow("Counterparty", it) }

        SheetSection("Explore")
        if (txn.merchant != null || txn.description.isNotBlank()) {
            ExploreRow("Everything at ${txn.title}") { actions.onOpenLedger(LedgerFilter(query = txn.merchant ?: txn.description), txn.title) }
        }
        txn.category?.let { category ->
            ExploreRow("All ${Labels.humanize(category)}") { actions.onOpenLedger(LedgerFilter(categories = listOf(category)), Labels.humanize(category)) }
        }
        ExploreRow("Activity on ${txn.accountName}") { actions.onOpenLedger(LedgerFilter(accounts = listOf(txn.accountId)), txn.accountName) }
        txn.cardholderName?.let { holder ->
            ExploreRow("Spent by $holder") { actions.onOpenLedger(LedgerFilter(cardholders = listOf(holder)), holder) }
        }
        txn.installmentPlanId?.let { plan ->
            ExploreRow("Instalment plan${txn.installmentSeq?.let { " · payment $it" }.orEmpty()}") { actions.onOpenInstallment(plan) }
        }
        txn.refundOfId?.let { purchase -> ExploreRow("Reverses a purchase") { actions.onOpenLinked(purchase) } }

        SheetSection(
            "Annotations",
            trailing = {
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            },
        )
        AnimatedContent(editing, transitionSpec = { crossfade() }, label = "annotations") { isEditing ->
            if (isEditing) {
                AnnotationEditor(txn, editor, actions)
            } else {
                Column {
                    FactRow("Notes", txn.notes?.takeIf { it.isNotBlank() } ?: "—")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tags", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        if (txn.tags.isEmpty()) {
                            Text("—", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        } else {
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                                txn.tags.forEach { tag ->
                                    AssistChip(onClick = { actions.onOpenLedger(LedgerFilter(tags = listOf(tag)), tag) }, label = { Text(tag) })
                                }
                            }
                        }
                    }
                }
            }
        }

        val legs = txn.transferLegs.filter { it.id != txn.id }
        if (legs.isNotEmpty()) {
            SheetSection("Transfer counterpart")
            legs.forEach { leg -> LinkedRowItem(leg, label = leg.role, onClick = { actions.onOpenLinked(leg.id) }) }
        }
        if (txn.relatedTransactions.isNotEmpty()) {
            SheetSection("Related")
            txn.relatedTransactions.forEach { row -> LinkedRowItem(row, label = row.relation, onClick = { actions.onOpenLinked(row.id) }) }
        }

        // Raw parser fields stay out of sight; the named facts open their rows.
        val facts = txn.detailStrings.filterKeys { !it.startsWith("raw") }
        if (facts.isNotEmpty()) {
            SheetSection("From the statement")
            facts.forEach { (key, value) ->
                FactRow(Labels.humanize(key), value, onClick = {
                    actions.onOpenLedger(LedgerFilter(detail = listOf("$key=$value")), "${Labels.humanize(key)}: $value")
                })
            }
        }
        txn.provenance?.let { provenance ->
            SheetSection("Source")
            provenance.sourcePath?.let { FactRow("File", it.substringAfterLast('/')) }
            provenance.parserId?.let { FactRow("Parser", it) }
            provenance.importedAt?.let { FactRow("Imported", DisplayDates.medium(it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationEditor(txn: Txn, editor: EditorState, actions: DetailActions) {
    var merchant by rememberSaveable(txn.id, txn.merchant) { mutableStateOf(txn.merchant.orEmpty()) }
    var notes by rememberSaveable(txn.id, txn.notes) { mutableStateOf(txn.notes.orEmpty()) }
    var tag by rememberSaveable(txn.id) { mutableStateOf("") }
    val dirty = merchant.trim() != txn.merchant.orEmpty().trim() || notes.trim() != txn.notes.orEmpty().trim()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            merchant,
            onValueChange = { merchant = it },
            label = { Text("Merchant") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            txn.tags.forEach { existing ->
                InputChip(
                    selected = false,
                    onClick = { actions.onRemoveTag(existing) },
                    label = { Text(existing) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove $existing", modifier = Modifier.size(16.dp)) },
                    enabled = !editor.saving,
                )
            }
        }
        OutlinedTextField(
            tag,
            onValueChange = { tag = it },
            label = { Text("Add tag") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                actions.onAddTag(tag)
                tag = ""
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    actions.onSetMerchant(merchant)
                    actions.onSetNotes(notes)
                },
                enabled = dirty && !editor.saving,
            ) { Text(if (editor.saving) "Saving…" else "Save") }
            OutlinedButton(
                onClick = {
                    merchant = txn.merchant.orEmpty()
                    notes = txn.notes.orEmpty()
                },
                enabled = dirty && !editor.saving,
            ) { Text("Revert") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(txn: Txn, editor: EditorState, actions: DetailActions, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text(txn.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
        }
        when {
            editor.suggestionLoading -> SkeletonBlock(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), height = 64.dp)
            editor.suggestion != null -> {
                val guess = editor.suggestion
                Card(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        actions.onAcceptSuggestion(guess)
                        onDone()
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Suggested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                listOfNotNull(guess.category, guess.subcategory).joinToString(" › ") { Labels.humanize(it) },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            guess.merchant?.takeIf { it != txn.merchant }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Text(
                            "${(guess.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        SheetSection("All categories")
        if (editor.categories.isEmpty()) {
            SkeletonBlock(Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 120.dp)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            editor.categories.forEach { category ->
                FilterChip(
                    selected = category == txn.category,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (category != txn.category) actions.onSetCategory(category)
                        onDone()
                    },
                    label = { Text(Labels.humanize(category)) },
                )
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, trailing: (@Composable () -> Unit)? = null) {
    HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun FactRow(label: String, value: String, onClick: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(start = 24.dp, end = if (action != null) 12.dp else 24.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun ExploreRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkedRowItem(row: LinkedRow, label: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.descriptionRaw, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(label?.let(Labels::humanize), DisplayDates.short(row.txnDate)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyText(row.amount, signed = true, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        textAlign = TextAlign.Center,
    )
}
