package page.zepto.finto.feature.recurring

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.Txn

@Composable
fun RecurringDetailRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenTransaction: (LedgerFilter, String?, String) -> Unit = { filter, title, _ -> onOpenLedger(filter, title) },
    viewModel: RecurringDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecurringDetailScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenInBlotter = { commitment -> onOpenLedger(blotterFilter(commitment), commitment.merchant) },
        onOpenCharge = { txn -> onOpenTransaction(chargeFilter(txn), txn.title, txn.id) },
        onNoticeShown = viewModel::noticeShown,
    )
}

/** Every charge to the merchant, as the web's "Open in blotter" (`q=<merchant>&tags=subscription`). */
internal fun blotterFilter(commitment: Commitment) = LedgerFilter(query = commitment.merchant, tags = listOf("subscription"))

/** The blotter on the day and account of one charge: the closest the blotter gets to a single row. */
internal fun chargeFilter(txn: Txn) = LedgerFilter(accounts = listOf(txn.accountId), from = txn.date, to = txn.date)

@Composable
fun RecurringDetailScreen(
    state: RecurringDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenInBlotter: (Commitment) -> Unit,
    onOpenCharge: (Txn) -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(state.notice, snackbar, onNoticeShown)
    val commitment = state.commitment
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                title = commitment?.merchant ?: "Recurring",
                subtitle = commitment?.let { "${it.cadence.label} · ${plural(it.count, "charge")}" },
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> Phase.Loading
            state.error != null && commitment == null -> Phase.Failed
            else -> Phase.Content
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "recurring-detail", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                Phase.Loading -> ListSkeleton(rows = 4)
                Phase.Failed -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                Phase.Content -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    if (commitment == null) {
                        MessageState(Icons.Outlined.SearchOff, "No longer listed", "No charges tagged subscription or recurring for this merchant.")
                    } else {
                        CommitmentDetail(commitment, onOpenInBlotter, onOpenCharge)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitmentDetail(commitment: Commitment, onOpenInBlotter: (Commitment) -> Unit, onOpenCharge: (Txn) -> Unit) {
    val history = remember(commitment) { commitment.charges.sortedByDescending { it.date } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (commitment.inactive) {
            item(key = "inactive") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        "Inactive",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "No charge since ${DisplayDates.short(commitment.lastCharge)} · not counted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "facts") {
            StatGrid(Modifier.padding(top = 8.dp)) {
                val tile = Modifier.weight(1f)
                StatTile("Typical", MoneyFormat.format(commitment.amount, absolute = true), tile)
                StatTile("Per month", commitment.monthly?.let { MoneyFormat.format(it, absolute = true) } ?: "—", tile)
                StatTile("Average", MoneyFormat.format(commitment.average, absolute = true), tile)
                StatTile("Total paid", MoneyFormat.format(commitment.totalPaid, absolute = true), tile)
                StatTile("First seen", DisplayDates.short(commitment.firstSeen), tile)
                StatTile("Last charge", DisplayDates.short(commitment.lastCharge), tile)
                StatTile("Next due", commitment.nextDue?.let { DisplayDates.short(it) } ?: "—", tile)
                StatTile("Category", commitment.category?.let(Labels::humanize) ?: "—", tile)
            }
        }
        item(key = "open") {
            FilledTonalButton(
                onClick = { onOpenInBlotter(commitment) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open in blotter")
            }
        }
        item(key = "history-header") {
            SectionHeader("Charge history", trailing = { Count(commitment.count) })
        }
        items(history, key = { it.id }) { txn -> ChargeRow(txn, onClick = { onOpenCharge(txn) }, modifier = Modifier.animateItem()) }
    }
}

@Composable
private fun ChargeRow(txn: Txn, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show in blotter", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(DisplayDates.medium(txn.date), style = MaterialTheme.typography.bodyLarge)
            Text(
                txn.accountName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.bodyLarge)
    }
}
