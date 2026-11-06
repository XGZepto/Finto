package page.zepto.finto.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Txn

@Composable
fun TransactionRow(txn: Txn, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    ListItem(
        modifier = modifier.combinedClickable(
            onClickLabel = "Show details",
            onClick = onClick,
            onLongClickLabel = onLongClick?.let { "Categorise" },
            onLongClick = onLongClick?.let { longClick ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
        ),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    kindIcon(txn),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(txn.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (txn.reviewState == "flagged") {
                    Icon(
                        Icons.Outlined.Flag,
                        contentDescription = "Flagged for review",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(
                listOfNotNull(txn.accountName, txn.category?.let(Labels::humanize) ?: Labels.kind(txn.kind)).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.bodyLarge)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

internal fun kindIcon(txn: Txn): ImageVector = when {
    txn.isTransfer -> Icons.AutoMirrored.Outlined.CompareArrows
    else -> categoryIcon(txn.category) ?: movementIcon(txn)
}

/** The ledger's categories are free text; match on the common stems. */
private fun categoryIcon(category: String?): ImageVector? {
    val key = category?.lowercase() ?: return null
    return when {
        "dining" in key || "restaurant" in key || "food" in key -> Icons.Outlined.Restaurant
        "grocer" in key -> Icons.Outlined.LocalGroceryStore
        "transport" in key || "transit" in key || "taxi" in key -> Icons.Outlined.DirectionsTransit
        "travel" in key || "flight" in key -> Icons.Outlined.Flight
        "subscription" in key -> Icons.Outlined.Autorenew
        "utilit" in key || "bill" in key -> Icons.Outlined.Bolt
        "health" in key || "medical" in key -> Icons.Outlined.LocalHospital
        "entertain" in key -> Icons.Outlined.Movie
        "housing" in key || "rent" in key || "home" in key -> Icons.Outlined.Home
        "education" in key -> Icons.Outlined.School
        "income" in key || "salary" in key -> Icons.Outlined.Payments
        else -> null
    }
}

private fun movementIcon(txn: Txn): ImageVector = when {
    txn.kind == "cc_payment" -> Icons.Outlined.CreditCard
    txn.kind == "fx_conversion" -> Icons.Outlined.CurrencyExchange
    txn.kind == "refund" -> Icons.Outlined.Replay
    txn.kind == "interest" || txn.kind == "income" || txn.booked.amount > 0 -> Icons.Outlined.Savings
    txn.kind == "fee" -> Icons.Outlined.ReceiptLong
    else -> Icons.Outlined.ShoppingBag
}
