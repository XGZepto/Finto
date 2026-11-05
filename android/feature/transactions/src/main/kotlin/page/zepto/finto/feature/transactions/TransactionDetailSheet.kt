package page.zepto.finto.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.Txn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(state: DetailState, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        TransactionDetail(state)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionDetail(state: DetailState, modifier: Modifier = Modifier) {
    val txn = (state as? DetailState.Loaded)?.full ?: state.summary
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(txn.title, style = MaterialTheme.typography.titleLarge)
        if (txn.merchant != null && txn.merchant != txn.description) {
            Text(txn.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        when (state) {
            is DetailState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is DetailState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is DetailState.Loaded -> Unit
        }
        if (txn.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                txn.tags.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Fact("Date", DisplayDates.medium(txn.date))
        txn.postedDate?.takeIf { it != txn.date }?.let { Fact("Posted", DisplayDates.medium(it)) }
        Fact("Account", txn.accountName + (txn.cardLast4?.let { " · card ··$it" } ?: ""))
        txn.cardholderName?.let { Fact("Cardholder", it) }
        Fact("Category", listOfNotNull(txn.category, txn.subcategory).joinToString(" › ") { Labels.humanize(it) }.ifEmpty { "Uncategorised" })
        Fact("Kind", Labels.kind(txn.kind))
        txn.counterparty?.let { Fact("Counterparty", it) }
        txn.native?.takeIf { it.currency != txn.booked.currency }?.let { native ->
            Fact("Original amount", MoneyFormat.format(native))
            txn.fxRate?.let { Fact("Rate", it) }
            txn.fxFee?.let { Fact("FX fee", MoneyFormat.format(it)) }
        }
        if (txn.isTransfer) Fact("Transfer", "Linked to the other leg")
        txn.notes?.takeIf { it.isNotBlank() }?.let { Fact("Notes", it) }
        val facts = txn.detailStrings
        if (facts.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("From the statement", style = MaterialTheme.typography.titleSmall)
            facts.forEach { (key, value) -> Fact(Labels.humanize(key), value) }
        }
        txn.provenance?.let { provenance ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            provenance.sourcePath?.let { Fact("Source", it.substringAfterLast('/')) }
            provenance.importedAt?.let { Fact("Imported", DisplayDates.medium(it)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
