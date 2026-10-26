package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import com.neozeon.kubedroid.data.model.ResourceSummary
import com.neozeon.kubedroid.navigation.ResourceDetailKey
import java.util.UUID

/**
 * Spec 04 "ResourceListScreen": rows from the catalog extractor, search box,
 * namespace scope dropdown, infinite scroll on the `continue` token, and the
 * 403 "pick a namespace" escape hatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceListScreen(
    connectionId: UUID,
    apiVersion: String,
    resource: String,
    viewModel: ResourceListViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenDetail: (ResourceDetailKey) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val entry = ResourceCatalog.find(apiVersion, resource)
    val listState = rememberLazyListState()

    LaunchedEffect(connectionId, apiVersion, resource) {
        viewModel.open(ResourceListViewModel.Target(connectionId, apiVersion, resource))
    }

    // Infinite scroll (spec 04 human decision 2026-10-25): auto-fetch the
    // next `continue` page as the list end approaches.
    LaunchedEffect(listState, state.continueToken, state.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (shouldFetchMore(last, state.visibleItems.size, state.continueToken != null, state.loading)) {
                    viewModel.loadMore()
                }
            }
    }

    var scopeMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(entry?.displayName ?: resource, modifier = Modifier.testTag("list-title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("list-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { scopeMenuOpen = true },
                            modifier = Modifier.testTag("namespace-scope"),
                        ) {
                            Text(state.namespace ?: "All namespaces")
                        }
                        DropdownMenu(expanded = scopeMenuOpen, onDismissRequest = { scopeMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("All namespaces") },
                                onClick = {
                                    scopeMenuOpen = false
                                    viewModel.selectNamespace(null)
                                },
                            )
                            state.namespaces.forEach { ns ->
                                DropdownMenuItem(
                                    text = { Text(ns) },
                                    onClick = {
                                        scopeMenuOpen = false
                                        viewModel.selectNamespace(ns)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("refresh")) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.search,
                onValueChange = { viewModel.onSearch(it) },
                placeholder = { Text("Filter by name or label") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("search"),
            )

            val err = state.error
            when {
                err != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(err.userMessage(), modifier = Modifier.testTag("list-error"))
                    if (err is com.neozeon.kubedroid.data.model.ResourceError.Forbidden &&
                        state.namespace == null
                    ) {
                        Text(
                            "Tip: pick a namespace you have access to (top right).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("forbidden-hint"),
                        )
                    }
                    Button(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("retry")) {
                        Text("Retry")
                    }
                }

                state.loading && state.items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("list-loading"))
                }

                state.visibleItems.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No ${entry?.displayName?.lowercase() ?: resource} found",
                        modifier = Modifier.testTag("empty-state"),
                    )
                }

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("resource-list")) {
                    items(state.visibleItems, key = { "${it.namespace}/${it.name}" }) { summary ->
                        SummaryRow(
                            summary,
                            onClick = {
                                onOpenDetail(
                                    ResourceDetailKey(
                                        connectionId, apiVersion, resource, summary.namespace, summary.name,
                                    ),
                                )
                            },
                            onDelete = { viewModel.requestDelete(summary) },
                        )
                    }
                    item(key = "footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.testTag("loading-more"))
                            } else if (state.continueToken == null && state.items.isNotEmpty()) {
                                Text(
                                    "End of list",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("list-end"),
                                )
                            }
                        }
                    }
                }
            }

            // spec 06 "Delete flow": shared dialog + banners for the row overflow entry.
            state.deleteTarget?.let { summary ->
                DeleteConfirmDialog(
                    kind = summary.kind.ifBlank { entry?.kind ?: "resource" },
                    name = summary.name,
                    namespace = summary.namespace,
                    caution = (summary.kind.ifBlank { entry?.kind ?: "" })
                        .let { ResourceCatalog.cascadeCaution(it) },
                    deleting = state.deleting,
                    onConfirm = { viewModel.confirmDelete() },
                    onDismiss = { viewModel.dismissDelete() },
                )
            }
            state.deleteError?.let { de ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("list-delete-error"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        de.userMessage(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.dismissDeleteError() }) { Text("Dismiss") }
                }
            }
            state.deleteInfo?.let { DeleteInfoBanner(it, modifier = Modifier.padding(16.dp)) }
        }
    }
}

/** Infinite-scroll trigger: near the end of the loaded window with a token left. */
fun shouldFetchMore(lastVisibleIndex: Int?, itemCount: Int, hasToken: Boolean, loading: Boolean): Boolean =
    hasToken && !loading && lastVisibleIndex != null && itemCount > 0 && lastVisibleIndex >= itemCount - 5

@Composable
private fun SummaryRow(summary: ResourceSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("row-${summary.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(summary.name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.namespace?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(summary.primary, style = MaterialTheme.typography.bodySmall)
                summary.secondary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // spec 06 "Delete flow": row overflow Delete (same dialog as detail).
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("row-menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Row actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("row-delete"),
                )
            }
        }
    }
}
