package page.zepto.finto.feature.imports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ImportedFile
import page.zepto.finto.core.model.MoneyFormat

/** Everything the import page links to beyond its own flow. */
data class ImportActions(
    val onOpenRows: (statementId: String, uncategorisedOnly: Boolean) -> Unit = { _, _ -> },
    val onOpenTransaction: (statementId: String, txnId: String) -> Unit = { _, _ -> },
    val onOpenAccount: (String) -> Unit = {},
    val onOpenMatching: () -> Unit = {},
    val onToggleHistory: (ImportedFile) -> Unit = {},
    val onRerunHistoryReview: () -> Unit = {},
    val onRetryReview: (String) -> Unit = {},
    val onMaintenance: (Maintenance) -> Unit = {},
)

@Composable
internal fun ReviewLoadPanel(load: ReviewLoad, actions: ImportActions, onRetry: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    when (load) {
        ReviewLoad.Idle -> Unit
        ReviewLoad.Running -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Categorising, tagging and checking links…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SkeletonBlock(Modifier.fillMaxWidth(), height = 120.dp)
        }
        is ReviewLoad.Failed -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(load.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
        is ReviewLoad.Done -> ReviewPanel(load.review, actions, trailing)
    }
}

/** What the post-import pass found: coverage, links still open, and rows to look at. */
@Composable
fun ReviewPanel(review: ImportReview, actions: ImportActions, trailing: (@Composable () -> Unit)? = null) {
    val statement = review.statement.id
    val clean = review.summary.status == "clean"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (clean) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (clean) Icons.Outlined.TaskAlt else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(review.summary.headline, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (review.summary.model != null) {
                        Text("Claude", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                review.summary.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        trailing?.let { action ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { action() }
        }
        val rows = review.statement.rows
        val categorised = ImportLabels.categorised(review)
        val uncategorised = review.categories.bySource["uncategorised"] ?: 0
        val open = review.links.openTransferCandidates + review.links.openDuplicateCandidates
        StatGrid {
            StatTile("Rows imported", count(rows), Modifier.weight(1f), onClick = { actions.onOpenRows(statement, false) })
            StatTile(
                "Categorised",
                ImportLabels.share(categorised, rows),
                Modifier.weight(1f),
                supporting = "${review.categories.bySource["model"] ?: 0} by Claude",
                onClick = { actions.onOpenRows(statement, false) },
            )
            StatTile(
                "Uncategorised",
                count(uncategorised),
                Modifier.weight(1f),
                onClick = if (uncategorised > 0) ({ actions.onOpenRows(statement, true) }) else null,
            )
            StatTile("Payments linked", "${review.links.linked}/${review.links.movements}", Modifier.weight(1f), onClick = { actions.onOpenRows(statement, false) })
            StatTile(
                "Suggestions to decide",
                open.toString(),
                Modifier.weight(1f),
                valueColor = if (open > 0) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Unspecified,
                onClick = if (open > 0) actions.onOpenMatching else null,
            )
            StatTile(
                "Tags",
                review.categories.tags.toString(),
                Modifier.weight(1f),
                supporting = "${review.categories.merchantsNamed} names",
                onClick = { actions.onOpenRows(statement, false) },
            )
        }
        val notes = listOfNotNull(
            review.categories.movementFixed.takeIf { it > 0 }?.let { "Moved $it card payment${if (it == 1) "" else "s"} out of spending and under transfers." },
            review.links.mergedDuplicates.takeIf { it > 0 }?.let { "$it row${if (it == 1) " was" else "s were"} already in the ledger and merged." },
            "Claude is off for this ledger, so only rules categorised these rows.".takeIf { !review.model.available },
        )
        notes.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (review.links.unlinked.isNotEmpty()) {
            Label("Payments without their other leg")
            review.links.unlinked.forEach { row ->
                ReviewRow(
                    leading = DisplayDates.short(row.date),
                    title = row.description,
                    subtitle = row.account,
                    trailing = row.amount,
                    onClick = { actions.onOpenTransaction(statement, row.id) },
                )
            }
        }
        if (review.categories.topUncategorised.isNotEmpty()) {
            Label("Still uncategorised")
            review.categories.topUncategorised.forEach { group ->
                ReviewRow(null, group.description, null, "${group.rows} row${if (group.rows == 1) "" else "s"}", onClick = { actions.onOpenRows(statement, true) })
            }
        }
        review.balances.forEach { balance ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { actions.onOpenAccount(balance.accountId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${balance.accountName} ${DisplayDates.short(balance.periodStart)} – ${DisplayDates.short(balance.periodEnd)}: off by ${MoneyFormat.format(balance.discrepancy)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

@Composable
private fun ReviewRow(leading: String?, title: String, subtitle: String?, trailing: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(trailing, style = MaterialTheme.typography.bodyMedium.tabular(), color = if (trailing.startsWith("-")) MaterialTheme.colorScheme.onSurface else FintoThemeTokens.money.positive.takeIf { trailing.startsWith("+") } ?: MaterialTheme.colorScheme.onSurface)
    }
}

internal fun count(value: Int): String = java.text.NumberFormat.getIntegerInstance().format(value)
