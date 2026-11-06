package page.zepto.finto.feature.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.ChangeProposal
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat

/**
 * A correction Ask proposed: what it does, how far it reaches, how it moves
 * spending, a sample of the rows, and the one decision. Nothing changes until
 * Apply; once applied it can be undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProposalCard(
    proposal: ChangeProposal,
    busy: Boolean,
    error: String?,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (proposal.status) {
        "proposed" -> MaterialTheme.colorScheme.primary
        "applied" -> FintoThemeTokens.money.positive
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (proposal.status == "applied") Icons.Outlined.CheckCircle else Icons.Outlined.AutoFixHigh,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(ProposalText.status(proposal), style = MaterialTheme.typography.labelMedium, color = accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    proposal.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(ProposalText.change(proposal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ProposalText.rows(proposal.rows), style = MaterialTheme.typography.labelLarge)
                proposal.totals.forEach { MoneyText(it.money, style = MaterialTheme.typography.labelLarge) }
                proposal.spending.forEach { change ->
                    val lower = change.change < 0
                    Text(
                        "spending ${if (lower) "−" else "+"}${MoneyFormat.format(Money(kotlin.math.abs(change.change), change.currency))}",
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        fontWeight = FontWeight.SemiBold,
                        color = if (lower) FintoThemeTokens.money.positive else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (proposal.unchanged > 0) {
                    Text("${proposal.unchanged} already right", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (proposal.sample.isNotEmpty()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    proposal.sample.forEach { row ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    DisplayDates.short(row.date),
                                    style = MaterialTheme.typography.labelMedium.tabular(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(52.dp),
                                )
                                Text(
                                    row.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                MoneyText(row.amount, signed = true, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                "${row.accountName} · ${ProposalText.row(row.before, row.after)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 52.dp, top = 2.dp),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (proposal.rows > proposal.sample.size) {
                        Text(
                            "and ${proposal.rows - proposal.sample.size} more",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            AnimatedContent(proposal.status to busy, transitionSpec = { crossfade() }, label = "proposal") { (status, working) ->
                when (status) {
                    "proposed" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApply, enabled = !working && proposal.rows > 0) {
                            if (working) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (working) "Applying" else "Apply")
                        }
                        OutlinedButton(onClick = onDismiss, enabled = !working) { Text("Dismiss") }
                    }
                    "applied" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append("Changed ${ProposalText.rows(proposal.applied ?: 0)}")
                                proposal.skipped?.takeIf { it > 0 }?.let { append(" · $it edited since, left alone") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onUndo, enabled = !working) { Text(if (working) "Undoing" else "Undo") }
                    }
                    "undone" -> Text(
                        buildString {
                            append("Put back ${ProposalText.rows(proposal.restored ?: 0)}")
                            proposal.skipped?.takeIf { it > 0 }?.let { append(" · $it edited since, kept") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.height(40.dp).padding(top = 10.dp),
                    )
                    else -> Text("Nothing changed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
