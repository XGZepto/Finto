package page.zepto.finto.feature.health

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue

@Composable
fun ReviewRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewScreen(
        state = state,
        onBack = onBack,
        onSelect = viewModel::select,
        onMove = viewModel::move,
        onResolve = viewModel::resolve,
        onOpenRow = { account, date, title -> onOpenLedger(ReviewLogic.blotterFilter(account, date), title) },
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onMessageShown = viewModel::messageShown,
    )
}

private enum class Phase { Loading, Failed, Empty, Candidate }

@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onSelect: (ReviewQueue) -> Unit,
    onMove: (Int) -> Unit,
    onResolve: (accept: Boolean) -> Unit,
    onOpenRow: (account: String, date: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // The tabs sit under the bar and take its colour as content scrolls beneath.
    val tabColor by animateColorAsState(
        if (scrollBehavior.state.overlappedFraction > 0.01f) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tween(FintoMotion.MEDIUM),
        label = "tab-colour",
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                FintoTopBar("Matching suggestions", onBack = onBack, scrollBehavior = scrollBehavior)
                QueueTabs(state, tabColor, onSelect)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = state.candidate != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                DecisionBar(
                    onReject = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        onResolve(false)
                    },
                    onAccept = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onResolve(true)
                    },
                )
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> Phase.Loading
            state.current.error != null && state.items.isEmpty() -> Phase.Failed
            state.items.isEmpty() -> Phase.Empty
            else -> Phase.Candidate
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            AnimatedContent(
                targetState = state.queue to phase,
                transitionSpec = { crossfade() },
                label = "review-phase",
            ) { (queue, shown) ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (shown) {
                        Phase.Loading -> CandidateSkeleton()
                        Phase.Failed -> ErrorState(state.current.error.orEmpty(), onRetry = onRetry)
                        Phase.Empty -> MessageState(Icons.Outlined.TaskAlt, "Queue clear", ReviewLogic.emptyLine(queue))
                        Phase.Candidate -> CandidatePager(state, onMove, onOpenRow)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTabs(state: ReviewUiState, color: androidx.compose.ui.graphics.Color, onSelect: (ReviewQueue) -> Unit) {
    PrimaryTabRow(selectedTabIndex = state.queue.ordinal, containerColor = color) {
        ReviewQueue.entries.forEach { queue ->
            val selected = state.queue == queue
            val count = state.count(queue)
            Tab(
                selected = selected,
                onClick = { onSelect(queue) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = if (count > 0) "${queue.title}, $count open" else queue.title
                },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(queue.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (count > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) { Text(count.toString(), style = MaterialTheme.typography.labelSmall.tabular()) }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DecisionBar(onReject: () -> Unit, onAccept: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reject")
            }
            Button(onClick = onAccept, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Accept")
            }
        }
    }
}

@Composable
private fun CandidatePager(
    state: ReviewUiState,
    onMove: (Int) -> Unit,
    onOpenRow: (account: String, date: String, title: String) -> Unit,
) {
    val items = state.items
    AnimatedContent(
        targetState = state.candidate,
        contentKey = { it?.id },
        transitionSpec = {
            // Forward when moving on or when the shown one was decided; back when stepping back.
            val from = items.indexOfFirst { it.id == initialState?.id }
            val to = items.indexOfFirst { it.id == targetState?.id }
            val forward = from == -1 || to >= from
            val sign = if (forward) 1 else -1
            (
                slideInHorizontally(tween(FintoMotion.MEDIUM, easing = FintoMotion.EmphasizedDecelerate)) { sign * it / 4 } +
                    fadeIn(tween(220, delayMillis = 60, easing = FintoMotion.Standard))
                ) togetherWith (
                slideOutHorizontally(tween(200, easing = FintoMotion.EmphasizedAccelerate)) { -sign * it / 4 } +
                    fadeOut(tween(120, easing = FintoMotion.Standard))
                ) using SizeTransform(clip = false)
        },
        label = "candidate",
    ) { candidate ->
        if (candidate != null) {
            CandidateCard(
                item = candidate,
                queue = state.queue,
                position = items.indexOfFirst { it.id == candidate.id }.coerceAtLeast(0),
                size = items.size,
                accountName = state::accountName,
                onMove = onMove,
                onOpenRow = onOpenRow,
            )
        }
    }
}

@Composable
private fun CandidateCard(
    item: ReviewCandidate,
    queue: ReviewQueue,
    position: Int,
    size: Int,
    accountName: (String?) -> String,
    onMove: (Int) -> Unit,
    onOpenRow: (account: String, date: String, title: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        CandidateHeader(item.score, position, size, onMove)
        val sides = ReviewLogic.sides(queue, item)
        if (sides.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sides.forEach { side ->
                    SidePane(side, accountName(side.account), onOpen = { onOpenRow(side.account, side.date, side.description) })
                }
            }
        } else {
            InstalmentGroup(item, accountName(item.accountId), onOpenRow)
        }
        Reasons(item)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            ReviewLogic.consequence(queue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun CandidateHeader(score: Double, position: Int, size: Int, onMove: (Int) -> Unit) {
    val band = ReviewLogic.band(score)
    val (container, content) = when (band) {
        ScoreBand.High -> FintoThemeTokens.money.positiveContainer to FintoThemeTokens.money.positive
        ScoreBand.Medium -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ScoreBand.Low -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = container, contentColor = content, shape = CircleShape, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    ReviewLogic.percent(score).toString(),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    modifier = Modifier.semantics { contentDescription = "Score ${ReviewLogic.percent(score)}" },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(band.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${position + 1} of $size",
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = position > 0) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous suggestion")
        }
        IconButton(onClick = { onMove(1) }, enabled = position < size - 1) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next suggestion")
        }
    }
}

@Composable
private fun SidePane(side: CandidateSide, account: String, onOpen: () -> Unit) {
    val (container, content) = when (side.role) {
        "Keep", "In" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "Drop" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(side.role, container, content)
                Spacer(Modifier.width(8.dp))
                Text(
                    account,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DisplayDates.short(side.date),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            MoneyText(side.amount, style = MaterialTheme.typography.titleLarge, colorize = true)
            Text(
                side.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            OpenInBlotter(onOpen)
        }
    }
}

@Composable
private fun OpenInBlotter(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text("Open in blotter", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun InstalmentGroup(
    item: ReviewCandidate,
    account: String,
    onOpenRow: (account: String, date: String, title: String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.description.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                item.termMonths?.let {
                    Spacer(Modifier.width(8.dp))
                    Pill("$it months", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (account.isNotBlank()) {
                Text(
                    account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            item.transactions.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Open in blotter") { onOpenRow(item.accountId.orEmpty(), row.txnDate, row.descriptionRaw) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        DisplayDates.short(row.txnDate),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        row.descriptionRaw,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    MoneyText(row.amount, style = MaterialTheme.typography.bodyMedium, colorize = true)
                }
            }
        }
    }
}

@Composable
private fun Reasons(item: ReviewCandidate) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text("Scored on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (item.reasons.isEmpty()) {
            Text("No reasons recorded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            item.reasons.forEach { reason ->
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(14.dp))
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ReviewLogic.gap(item)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CandidateSkeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(Modifier.size(48.dp).clip(CircleShape), height = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBlock(Modifier.width(120.dp), height = 18.dp)
                SkeletonBlock(Modifier.width(56.dp), height = 12.dp)
            }
        }
        SkeletonBlock(Modifier.fillMaxWidth(), height = 132.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), height = 132.dp)
        SkeletonBlock(Modifier.width(200.dp), height = 14.dp)
        SkeletonBlock(Modifier.width(160.dp), height = 14.dp)
    }
}
