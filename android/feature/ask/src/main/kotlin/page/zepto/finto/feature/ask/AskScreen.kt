package page.zepto.finto.feature.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.Bar
import page.zepto.finto.core.designsystem.component.BarChart
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Drill
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.TotalRow
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.LedgerFilter

/** Questions to start from when the thread is empty, as the web app offers them. */
val AskExamples = listOf(
    "How much did I spend on dining last month",
    "What did I spend at supermarkets this year, by month",
    "Biggest purchases in October",
    "Uncategorised transactions over 1,000",
)

@Composable
fun AskRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is AskEffect.Finished) {
                haptics.performHapticFeedback(if (effect.ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            }
        }
    }
    AskScreen(
        state = state,
        onBack = onBack,
        onAsk = viewModel::ask,
        onStop = viewModel::stop,
        onNewConversation = viewModel::newConversation,
        onToggleRows = viewModel::toggleRows,
        onOpenLedger = onOpenLedger,
    )
}

@Composable
fun AskScreen(
    state: AskUiState,
    onBack: () -> Unit,
    onAsk: (question: String) -> Boolean,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onToggleRows: (turnId: Long) -> Unit,
    onOpenLedger: OpenLedger,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                "Ask",
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.turns.isNotEmpty()) TextButton(onClick = onNewConversation) { Text("New conversation") }
                },
            )
        },
        bottomBar = { PromptBar(asking = state.asking, followUp = state.turns.isNotEmpty(), onAsk = onAsk, onStop = onStop) },
    ) { padding ->
        if (state.turns.isEmpty()) {
            Examples(onAsk, Modifier.padding(padding))
        } else {
            ThreadList(state, onAsk, onToggleRows, onOpenLedger, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Examples(onAsk: (String) -> Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text("Ask about your money", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Spending, income, accounts or balances, worked out from your ledger.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        AskExamples.forEach { example ->
            OutlinedCard(onClick = { onAsk(example) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(example, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadList(
    state: AskUiState,
    onAsk: (String) -> Boolean,
    onToggleRows: (Long) -> Unit,
    onOpenLedger: OpenLedger,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastId = state.turns.lastOrNull()?.id
    // A new question goes to the top of the view, so its answer grows below it.
    LaunchedEffect(lastId) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(state.turns, key = { it.id }) { turn ->
            TurnView(
                turn = turn,
                asking = state.asking,
                expanded = turn.id in state.expanded,
                accountNames = state.accountNames,
                onAsk = onAsk,
                onToggleRows = { onToggleRows(turn.id) },
                onOpenLedger = onOpenLedger,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun TurnView(
    turn: AskTurn,
    asking: Boolean,
    expanded: Boolean,
    accountNames: Map<String, String>,
    onAsk: (String) -> Boolean,
    onToggleRows: () -> Unit,
    onOpenLedger: OpenLedger,
    modifier: Modifier = Modifier,
) {
    val data = turn.data
    val streaming = turn.state == TurnState.Asking
    Column(modifier.fillMaxWidth().animateContentSize(tween(220)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Question(turn.question)
        if (turn.steps.isNotEmpty() || streaming) Steps(turn, Modifier.padding(horizontal = 16.dp))

        if (turn.text.isNotEmpty()) {
            val revealed = rememberRevealedText(turn.text, streaming)
            val blocks = remember(revealed) { AnswerMarkdown.parse(revealed) }
            AnswerContent(blocks, caret = streaming, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (turn.state == TurnState.Error) {
            ErrorBanner(turn.error.orEmpty(), enabled = !asking, onRetry = { onAsk(turn.question) })
        }

        data.totals?.takeIf { it.isNotEmpty() }?.let { Appear { Totals(it) } }

        val rows = data.rows.orEmpty()
        if (AskAnswers.isSeries(rows)) {
            AskAnswers.series(rows).forEach { series ->
                Appear {
                    MonthChart(series) { month ->
                        onOpenLedger(Drill.into("month", month, AskAnswers.ledgerFilter(data.filter) ?: LedgerFilter()), AskAnswers.monthTitle(month))
                    }
                }
            }
        } else if (rows.isNotEmpty()) {
            val groupBy = data.groupBy
            val openRow: ((SummaryRow) -> Unit)? = if (groupBy != null && AskAnswers.canDrill(groupBy)) {
                { row ->
                    val scope = AskAnswers.ledgerFilter(data.filter) ?: LedgerFilter()
                    onOpenLedger(Drill.into(groupBy, row.bucket, scope), AskAnswers.bucketLabel(row.bucket, groupBy, accountNames))
                }
            } else {
                null
            }
            Appear {
                GroupedRows(
                    rows = AskAnswers.rankedRows(rows, expanded),
                    total = rows.size,
                    expanded = expanded,
                    groupBy = groupBy,
                    accountNames = accountNames,
                    onToggle = onToggleRows,
                    onOpen = openRow,
                )
            }
        }

        data.transactions?.items?.takeIf { it.isNotEmpty() }?.let { items -> Appear { Transactions(items.take(AskAnswers.ROW_CAP)) } }

        if (turn.state == TurnState.Done && (data.filter != null || data.groupBy != null)) {
            Footer(
                chips = AskAnswers.chips(data.filter, data.groupBy, accountNames),
                onViewRows = AskAnswers.ledgerFilter(data.filter)?.let { filter -> { onOpenLedger(filter, turn.question) } },
            )
        }
        if (turn.state == TurnState.Done) {
            AskAnswers.timing(turn.seconds, data.cached)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Fades and grows a block in the first time it appears, as figures land mid-answer. */
@Composable
private fun Appear(content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(220)) + expandVertically(tween(220))) { content() }
}

@Composable
private fun Question(text: String) {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Steps(turn: AskTurn, modifier: Modifier = Modifier) {
    val settled = turn.state != TurnState.Asking
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (settled) 2.dp else 6.dp)) {
        turn.steps.forEach { step -> StepLine(AskAnswers.stepLabel(step), step.state, settled) }
        if (!settled && turn.steps.none { it.state == "running" }) {
            val label = if (turn.steps.isEmpty() && turn.text.isEmpty()) "Reading the question" else "Writing the answer"
            StepLine(label, "running", settled = false)
        }
    }
}

@Composable
private fun StepLine(label: String, state: String, settled: Boolean) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(200))) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    "running" -> PulsingDot()
                    "error" -> Icon(Icons.Outlined.Close, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    else -> Icon(Icons.Outlined.Check, contentDescription = "Done", tint = FintoThemeTokens.money.positive, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = if (settled) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = if (state == "running") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A step still running: a dot that breathes, as on the web. */
@Composable
private fun PulsingDot() {
    val pulse = rememberInfiniteTransition(label = "step")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900, easing = FintoMotion.Standard), RepeatMode.Reverse),
        label = "step-scale",
    )
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = 0.3f + 0.7f * (scale - 0.6f) / 0.4f
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun ErrorBanner(message: String, enabled: Boolean, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onRetry, enabled = enabled) { Text("Ask again") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Totals(totals: List<TotalRow>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        totals.forEach { total ->
            val rows = "${total.txnCount} ${if (total.txnCount == 1) "row" else "rows"}"
            val income = total.income.takeIf { it.amount != 0L }?.let { " · ${AskAnswers.bare(it.abs())} in" }.orEmpty()
            StatTile(
                label = total.currency,
                value = AskAnswers.bare(total.spend.abs()),
                supporting = "out · $rows$income",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthChart(series: MonthSeries, onMonth: (String) -> Unit) {
    val bars = series.months.map { (month, value) ->
        Bar(
            key = month,
            label = DisplayDates.monthAbbreviation(month),
            value = value,
            description = "${AskAnswers.monthTitle(month)}: ${MoneyFormat.formatWhole(Money(value, series.currency))}",
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${series.currency} · out per month",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    MoneyFormat.formatWhole(Money(series.total, series.currency)),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                )
            }
            Spacer(Modifier.height(10.dp))
            BarChart(
                bars = bars,
                height = 120.dp,
                contentDescription = "${series.currency} spending per month. " + bars.joinToString("; ") { it.description },
                onBarClick = { onMonth(it.key) },
            )
        }
    }
}

@Composable
private fun GroupedRows(
    rows: List<SummaryRow>,
    total: Int,
    expanded: Boolean,
    groupBy: String?,
    accountNames: Map<String, String>,
    onToggle: () -> Unit,
    onOpen: ((SummaryRow) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .let { base -> if (onOpen != null) base.clickable(onClickLabel = "Show transactions") { onOpen(row) } else base }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    AskAnswers.bucketLabel(row.bucket, groupBy, accountNames),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(AskAnswers.bare(row.spend.abs()), style = MaterialTheme.typography.bodyMedium.tabular())
                Text(
                    " ${row.currency}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (total > AskAnswers.ROW_CAP) {
            TextButton(onClick = onToggle, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (expanded) "Show fewer" else "Show all $total")
            }
        }
    }
}

@Composable
private fun Transactions(items: List<Txn>) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        items.forEach { txn ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DisplayDates.short(txn.date),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(60.dp),
                    maxLines = 1,
                )
                Text(txn.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                MoneyText(txn.booked, style = MaterialTheme.typography.bodyMedium, colorize = true)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(chips: List<ScopeChip>, onViewRows: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { chip ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        chip.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (onViewRows != null) {
            TextButton(onClick = onViewRows) {
                Text("View rows")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PromptBar(asking: Boolean, followUp: Boolean, onAsk: (String) -> Boolean, onStop: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val send = { if (onAsk(input)) input = "" }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().keyboardPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (followUp) "Ask a follow-up" else "Ask about your money") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(8.dp))
            if (asking) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            } else {
                FilledIconButton(
                    onClick = send,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Ask" },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                }
            }
        }
    }
}
