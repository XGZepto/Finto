package page.zepto.finto.feature.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.LoadingState
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.model.DisplayDates

@Composable
fun ActivityRoute(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    ActivityScreen(
        state = state,
        items = items,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSpendingOnlyChange = viewModel::onSpendingOnlyChange,
        onAccountChange = viewModel::onAccountChange,
        onOpen = { viewModel.open(it.txn) },
    )
    detail?.let { TransactionDetailSheet(it, onDismiss = viewModel::closeDetail) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    items: LazyPagingItems<ActivityItem>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSpendingOnlyChange: (Boolean) -> Unit,
    onAccountChange: (AccountChoice?) -> Unit,
    onOpen: (ActivityItem.Row) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        state.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (state.scoped) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(state.query, onQueryChange)
            FilterRow(state, onSpendingOnlyChange, onAccountChange)
            PullToRefreshBox(
                isRefreshing = items.loadState.refresh is LoadState.Loading && items.itemCount > 0,
                onRefresh = { items.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                ActivityList(items, state, onOpen)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search descriptions and merchants") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterRow(
    state: ActivityUiState,
    onSpendingOnlyChange: (Boolean) -> Unit,
    onAccountChange: (AccountChoice?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            FilterChip(
                selected = state.account != null,
                enabled = !state.accountLocked,
                onClick = { menuOpen = true },
                label = { Text(state.account?.name ?: "All accounts") },
                leadingIcon = { Icon(Icons.Outlined.AccountBalance, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("All accounts") }, onClick = { onAccountChange(null); menuOpen = false })
                state.accounts.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.name) },
                        trailingIcon = { if (choice == state.account) Icon(Icons.Outlined.Check, null) },
                        onClick = { onAccountChange(choice); menuOpen = false },
                    )
                }
            }
        }
        FilterChip(
            selected = state.spendingOnly,
            onClick = { onSpendingOnlyChange(!state.spendingOnly) },
            label = { Text("Spending only") },
            leadingIcon = if (state.spendingOnly) {
                { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ActivityList(
    items: LazyPagingItems<ActivityItem>,
    state: ActivityUiState,
    onOpen: (ActivityItem.Row) -> Unit,
) {
    val refresh = items.loadState.refresh
    when {
        refresh is LoadState.Loading && items.itemCount == 0 -> LoadingState()
        refresh is LoadState.Error && items.itemCount == 0 ->
            ErrorState(ApiErrors.message(refresh.error), onRetry = { items.retry() })
        refresh is LoadState.NotLoading && items.itemCount == 0 -> MessageState(
            icon = if (state.query.isNotEmpty()) Icons.Outlined.SearchOff else Icons.Outlined.ReceiptLong,
            title = if (state.query.isNotEmpty()) "No matches" else "No transactions",
            body = if (state.query.isNotEmpty()) "Nothing matches “${state.query}”." else "",
        )
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(
                count = items.itemCount,
                key = items.itemKey { item ->
                    when (item) {
                        is ActivityItem.Day -> "day-${item.date}"
                        is ActivityItem.Row -> item.txn.id
                    }
                },
                contentType = items.itemContentType { it::class.simpleName },
            ) { index ->
                when (val item = items[index]) {
                    is ActivityItem.Day -> Text(
                        DisplayDates.header(item.date),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                    is ActivityItem.Row -> TransactionRow(item.txn, onClick = { onOpen(item) })
                    null -> Unit
                }
            }
            when (val append = items.loadState.append) {
                is LoadState.Loading -> item("append-loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                is LoadState.Error -> item("append-error") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { items.retry() }) { Text("${ApiErrors.message(append.error)} Retry") }
                    }
                }
                else -> Unit
            }
        }
    }
}
