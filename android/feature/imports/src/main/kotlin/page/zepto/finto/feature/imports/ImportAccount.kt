package page.zepto.finto.feature.imports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.DropdownChip
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Institution
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.StagePreview

/** What the preview's account controls call back into. */
data class AccountActions(
    val onChoose: (String) -> Unit = {},
    val onNew: () -> Unit = {},
    val onDraftChange: (AccountDraft) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onCancelDraft: () -> Unit = {},
)

/**
 * Where the statement's rows go. A statement that names its own account says
 * so; one that doesn't asks for an account before it can be imported.
 */
@Composable
internal fun AccountBinding(
    preview: StagePreview,
    state: ImportUiState,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = state.chosen
    val check = preview.accountCheck
    val missing = preview.needsAccount && chosen == null
    val (value, detail) = when {
        missing -> "Choose an account" to "None of your accounts matches this statement yet. Pick one, or add the account."
        chosen != null -> chosen.displayName to check?.takeIf { it.level == "ok" }?.title
        check?.level == "ok" -> check.title.removePrefix("Goes to ") to "Read from the statement"
        else -> "Read from the statement" to null
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (missing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable(role = Role.Button, onClickLabel = "Choose account", enabled = !state.rechecking, onClick = onOpenPicker)
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(onClick = onOpenPicker, enabled = !state.rechecking) { Text(if (missing) "Choose" else "Change") }
            }
            if (state.rechecking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
            if (state.draft == null) {
                state.accountError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The person's open accounts, the statement's issuer first, and a way to add one. */
@Composable
internal fun AccountPickerSheet(
    accounts: List<Account>,
    institutions: List<Institution>,
    selected: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "File this statement under",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item("new") {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onNew),
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text("New account", color = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("Filled in from this statement") },
                )
            }
            items(accounts, key = { it.id }) { account ->
                val issuer = institutions.firstOrNull { it.id == account.institutionId }?.displayName ?: Labels.humanize(account.institutionId)
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onSelect(account.id) },
                    leadingContent = {
                        Icon(
                            if (account.accountType.endsWith("card")) Icons.Outlined.CreditCard else Icons.Outlined.AccountBalance,
                            contentDescription = null,
                        )
                    },
                    headlineContent = { Text(account.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOf(issuer, AccountDrafts.typeLabel(account.accountType), account.primaryCurrency).joinToString(" · "))
                    },
                    trailingContent = { if (account.id == selected) Icon(Icons.Outlined.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}

/** A new account for the statement, prefilled from it; every field can be changed. */
@Composable
internal fun NewAccountSheet(
    draft: AccountDraft,
    institutions: List<Institution>,
    currencies: List<String>,
    creating: Boolean,
    error: String?,
    actions: AccountActions,
) {
    ModalBottomSheet(onDismissRequest = actions.onCancelDraft, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NewAccountForm(draft, institutions, currencies, creating, error, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewAccountForm(
    draft: AccountDraft,
    institutions: List<Institution>,
    currencies: List<String>,
    creating: Boolean,
    error: String?,
    actions: AccountActions,
    modifier: Modifier = Modifier,
) {
    val issuers = institutions.map { it.id }.ifEmpty { listOf(draft.institutionId) }.let {
        if (draft.institutionId in it || draft.institutionId.isEmpty()) it else listOf(draft.institutionId) + it
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New account", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = draft.displayName,
            onValueChange = { actions.onDraftChange(draft.copy(displayName = it)) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (issuers.isNotEmpty() && draft.institutionId.isNotEmpty()) {
                DropdownChip(
                    selected = draft.institutionId,
                    options = issuers,
                    label = { id -> institutions.firstOrNull { it.id == id }?.displayName ?: Labels.humanize(id) },
                    onSelect = { actions.onDraftChange(draft.copy(institutionId = it)) },
                    description = "Institution",
                )
            }
            DropdownChip(
                selected = draft.accountType,
                options = AccountDrafts.TYPES,
                label = AccountDrafts::typeLabel,
                onSelect = { actions.onDraftChange(draft.copy(accountType = it)) },
                description = "Type",
            )
            DropdownChip(
                selected = draft.currency,
                options = (listOf(draft.currency) + currencies).distinct(),
                label = { it },
                onSelect = { actions.onDraftChange(draft.copy(currency = it)) },
                description = "Currency",
            )
        }
        if (draft.isCard) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.last4,
                    onValueChange = { value -> actions.onDraftChange(draft.copy(last4 = value.filter(Char::isDigit).take(4))) },
                    label = { Text("Last 4 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(0.4f),
                )
                OutlinedTextField(
                    value = draft.cardholder,
                    onValueChange = { actions.onDraftChange(draft.copy(cardholder = it)) },
                    label = { Text("Cardholder") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(0.6f),
                )
            }
            if (draft.last4.length == 4) {
                Text(
                    "Later statements for card ··${draft.last4} go to this account on their own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = actions.onCancelDraft, enabled = !creating, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = actions.onCreate, enabled = !creating && draft.isComplete, modifier = Modifier.weight(1f)) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add and use")
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
