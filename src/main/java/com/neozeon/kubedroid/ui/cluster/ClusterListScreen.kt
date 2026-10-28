package com.neozeon.kubedroid.ui.cluster

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.LocalNetworkAccess
import com.neozeon.kubedroid.data.model.ClusterConnection
import com.neozeon.kubedroid.data.model.ConnectionStatus

/**
 * Home body (replaces the spec 02 placeholder; HomeKey unchanged):
 * connection rows with context dropdown, status dot, insecure-TLS badge,
 * long-press delete confirmation, FAB with File / Paste / QR import
 * (spec 03 "UI").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterListScreen(
    viewModel: ClusterListViewModel = hiltViewModel(),
    // spec 04 amendment: row tap sets active AND browses (stage 1 of the
    // spec 04 dev plan; default no-op keeps existing callers/tests compiling).
    onBrowse: (java.util.UUID) -> Unit = {},
) {
    val connections by viewModel.connections.collectAsState()
    val statuses by viewModel.statuses.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    var showAddSheet by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importFile(uri)
    }

    // spec 03 stage 9: Android 16+ drops LAN SYNs for apps targeting
    // SDK 36+ until ACCESS_LOCAL_NETWORK is granted — ask as soon as
    // there is something to connect to (helper is a no-op below API 36).
    val context = LocalContext.current
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* probe/list errors surface on their own; nothing else to gate */ }
    LaunchedEffect(connections.isEmpty()) {
        if (connections.isNotEmpty() && !LocalNetworkAccess.isGranted(context)) {
            localNetworkLauncher.launch(LocalNetworkAccess.PERMISSION)
        }
    }

    // spec 08 (Q7A): attribution for the Kubernetes Icons Set artwork.
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }, modifier = Modifier.testTag("about-dismiss")) {
                    Text("Close")
                }
            },
            text = {
                Text(
                    "Contains artwork from the Kubernetes Icons Set, " +
                        "copyright The Kubernetes Authors, used under the " +
                        "Apache License 2.0. Kubernetes is a registered " +
                        "trademark of The Linux Foundation.",
                    modifier = Modifier.testTag("about-attribution"),
                )
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("kubedroid") },
                actions = {
                    IconButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.testTag("about"),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "About")
                    }
                    IconButton(
                        onClick = { viewModel.refreshActive() },
                        modifier = Modifier.testTag("refresh"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Text("+", fontFamily = FontFamily.Default) },
                text = { Text("Add cluster") },
            )
        },
    ) { padding ->
        if (connections.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(padding),
                onFile = { runCatching {
                    fileLauncher.launch(arrayOf("*/*"))
                } },
                onPaste = { viewModel.openAdd() },
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                connections.forEach { connection ->
                    ConnectionRow(
                        connection = connection,
                        status = statuses[connection.id] ?: ConnectionStatus.Unvalidated,
                        onClick = {
                            viewModel.setActive(connection.id)
                            onBrowse(connection.id)
                        },
                        onLongClick = { viewModel.requestDelete(connection.id) },
                        onSelectContext = { viewModel.selectContext(connection.id, it) },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add cluster", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        showAddSheet = false
                        runCatching {
                            fileLauncher.launch(arrayOf("*/*"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("File") }
                Button(
                    onClick = {
                        showAddSheet = false
                        viewModel.openAdd()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Paste") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    draft?.let { current ->
        AddClusterDialog(current, viewModel)
    }

    pendingDelete?.let { id ->
        val row = connections.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Delete connection?") },
            text = { Text("This permanently removes ${row?.displayName ?: "the connection"} and its stored credentials. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete(id) }, modifier = Modifier.testTag("confirm-delete")) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }, modifier = Modifier.testTag("cancel-delete")) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onFile: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No cluster connected", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Paste kubeconfig") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Open kubeconfig file") }
    }
}

@Composable
private fun ConnectionRow(
    connection: ClusterConnection,
    status: ConnectionStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectContext: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("connection-row")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
                // Spec 03 "Connection probe": failure text is the mapped
                // user-friendly message; reachability shows the version tag.
                when (status) {
                    is ConnectionStatus.Reachable -> Text(
                        "v: ${status.serverVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.testTag("status-text"),
                    )
                    is ConnectionStatus.Unreachable -> Text(
                        // spec 03 stage 9: name the local-network permission
                        // when it is missing — a LAN timeout with no hint is
                        // otherwise indistinguishable from a dead cluster.
                        status.reason +
                            (LocalNetworkAccess.missingHint(LocalContext.current) ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        modifier = Modifier.testTag("status-text"),
                    )
                    ConnectionStatus.Unvalidated -> {}
                }
                if (connection.insecureTls) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "insecure TLS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFB300),
                        )
                    }
                }
            }
            ContextDropdown(
                contexts = connection.contexts,
                selected = connection.contextName,
                onSelect = onSelectContext,
            )
        }
    }
}

@Composable
private fun StatusDot(status: ConnectionStatus) {
    val color = when (status) {
        is ConnectionStatus.Reachable -> Color(0xFF4CAF50) // green
        is ConnectionStatus.Unreachable -> Color(0xFFF44336) // red
        ConnectionStatus.Unvalidated -> Color(0xFFFFC107) // yellow
    }
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .testTag("status-dot"),
    )
}

@Composable
private fun ContextDropdown(
    contexts: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedAssistChip(
        onClick = { expanded = true },
        label = { Text(selected, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.testTag("context-dropdown"),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        contexts.forEach { ctx ->
            DropdownMenuItem(
                text = { Text(ctx) },
                onClick = {
                    expanded = false
                    onSelect(ctx)
                },
                modifier = Modifier.testTag("context-item-$ctx"),
            )
        }
    }
}

@Composable
private fun AddClusterDialog(
    draft: ClusterListViewModel.AddDraft,
    viewModel: ClusterListViewModel,
) {
    AlertDialog(
        onDismissRequest = { viewModel.closeAdd() },
        title = { Text("Add cluster") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.raw,
                    onValueChange = { viewModel.updateRaw(it) },
                    label = { Text("kubeconfig (YAML)") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("kubeconfig-input"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("name-input"),
                )
                if (draft.parsed != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Initial context", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.parsed.contexts.forEach { ctx ->
                            TextButton(
                                onClick = { viewModel.updateContext(ctx.name) },
                                modifier = Modifier.testTag("draft-context-${ctx.name}"),
                            ) {
                                Text(
                                    ctx.name,
                                    color = if (ctx.name == draft.contextName)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                draft.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmAdd() },
                enabled = draft.parsed != null,
                modifier = Modifier.testTag("confirm-add"),
            ) { Text("Import cluster") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.closeAdd() }) { Text("Cancel") }
        },
    )
}
