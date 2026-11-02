package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import com.neozeon.kubedroid.ui.common.MetaGrid
import com.neozeon.kubedroid.ui.common.RelativeTime
import com.neozeon.kubedroid.ui.logs.LogNavPayloads
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.ui.status.StatusPill
import com.neozeon.kubedroid.ui.status.conditionChips
import com.neozeon.kubedroid.ui.status.resolveStatus
import java.time.Instant
import java.util.UUID

/**
 * Spec 04 "ResourceDetailScreen": Summary + YAML tabs for one object.
 * Secrets render redacted with a Reveal button (spec human decision
 * 2026-10-25); YAML is plain monospace, copyable, never editable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceDetailScreen(
    connectionId: UUID,
    apiVersion: String,
    resource: String,
    namespace: String?,
    name: String,
    viewModel: ResourceDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onDeleted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val entry = ResourceCatalog.find(apiVersion, resource)
    val isSecret = entry?.kind == "Secret"
    val isPod = entry?.kind == "Pod"
    val clipboard = LocalClipboardManager.current
    // screen-level dirty-exit confirmation (spec 06 "Edit flow")
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    // spec 12 goal 2 (Q4): YAML quick-peek bottom sheet, summary tab only.
    var yamlSheetOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.deletedEvent) {
        if (state.deletedEvent > 0) onDeleted()
    }

    fun requestExitEdit() {
        if (state.draft != state.initialDraft) showDiscardDialog = true
        else viewModel.exitEdit()
    }

    LaunchedEffect(connectionId, apiVersion, resource, namespace, name) {
        viewModel.open(ResourceRef(connectionId, apiVersion, resource, namespace, name))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // spec 08: kind icon in the detail header (Q6A).
                        entry?.takeIf { it.iconVector != 0 }?.let { e ->
                            Icon(
                                painterResource(e.iconVector),
                                contentDescription = e.kind,
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 8.dp),
                            )
                        }
                        Text(name, modifier = Modifier.testTag("detail-title"))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.editing) requestExitEdit() else onBack() },
                        modifier = Modifier.testTag("detail-back"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.editing) {
                        // spec 06: Save disabled in flight — double-tap = one PUT.
                        TextButton(
                            onClick = { viewModel.save() },
                            enabled = !state.saving,
                            modifier = Modifier.testTag("save-action"),
                        ) { Text(if (state.saving) "Saving…" else "Save") }
                        TextButton(
                            onClick = { requestExitEdit() },
                            modifier = Modifier.testTag("cancel-edit"),
                        ) { Text("Cancel") }
                    } else {
                        // spec 06: Edit on every catalog type (Q3); Secrets must be
                        // revealed first or their redaction placeholders would go on
                        // the wire (ViewModel rule, mirrored by hiding the action).
                        if (state.obj != null && (!isSecret || state.revealed)) {
                            TextButton(
                                onClick = { viewModel.startEdit() },
                                modifier = Modifier.testTag("edit-action"),
                            ) { Text("Edit") }
                        }
                        // spec 06: Delete on every catalog type (Q3: RBAC is the gate).
                        if (state.obj != null) {
                            TextButton(
                                onClick = { viewModel.requestDelete() },
                                modifier = Modifier.testTag("delete-action"),
                            ) { Text("Delete") }
                        }
                    }
                    // spec 05 amendment: Pods only — Logs opens the viewer.
                    if (!state.editing && isPod && state.obj != null) {
                        TextButton(
                            onClick = {
                                // Hand the container names to the viewer (no second GET, spec 05).
                                state.obj?.let {
                                    LogNavPayloads.put(
                                        connectionId, namespace ?: "default", name,
                                        ResourceCatalog.containerNames(it),
                                    )
                                }
                                onOpenLogs()
                            },
                            modifier = Modifier.testTag("open-logs"),
                        ) {
                            Text("Logs")
                        }
                    }
                    if (!state.editing && isSecret && state.obj != null && !state.revealed) {
                        TextButton(onClick = { viewModel.reveal() }, modifier = Modifier.testTag("reveal")) {
                            Text("Reveal")
                        }
                    }
                    if (!state.editing && state.tab == ResourceDetailViewModel.TAB_YAML) {
                        val yamlText = state.yaml
                        if (yamlText != null) {
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(yamlText)) },
                                modifier = Modifier.testTag("copy-yaml"),
                            ) { Text("Copy") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab, modifier = Modifier.testTag("detail-tabs")) {
                Tab(
                    selected = state.tab == ResourceDetailViewModel.TAB_SUMMARY,
                    onClick = {
                        yamlSheetOpen = false // sheet belongs to the summary view
                        viewModel.selectTab(ResourceDetailViewModel.TAB_SUMMARY)
                    },
                    text = { Text("Summary") },
                )
                Tab(
                    selected = state.tab == ResourceDetailViewModel.TAB_YAML,
                    onClick = {
                        yamlSheetOpen = false
                        viewModel.selectTab(ResourceDetailViewModel.TAB_YAML)
                    },
                    text = { Text("YAML") },
                )
            }

            // spec 06 "Delete flow": banners ABOVE the when — the content
            // branches fillMaxSize() and would clip them off screen
            // (same list-screen acceptance finding).
            state.deleteError?.let { de ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("delete-error"),
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

            val err = state.error
            when {
                state.editing -> Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = { viewModel.updateDraft(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("yaml-editor"),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                    state.validationError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("yaml-validation"),
                        )
                    }
                    state.saveError?.let { se ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("save-error"),
                        ) {
                            Text(se.userMessage(), color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = { viewModel.save() },
                                enabled = !state.saving,
                                modifier = Modifier.padding(top = 4.dp).testTag("save-retry"),
                            ) { Text("Retry") }
                        }
                    }
                }

                err != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(err.userMessage(), modifier = Modifier.testTag("detail-error"))
                    Button(onClick = { viewModel.open(ResourceRef(connectionId, apiVersion, resource, namespace, name)) },
                        modifier = Modifier.testTag("detail-retry")) { Text("Retry") }
                }

                state.loading && state.obj == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.testTag("detail-loading")) }

                state.tab == ResourceDetailViewModel.TAB_YAML -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                        .testTag("yaml-view"),
                ) {
                    Text(
                        state.yaml ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag("summary-view"),
                ) {
                    // spec 12 goal 2/4: identity header — token pill,
                    // condition chips, monospace metadata grid — above the
                    // existing spec 04 key/value rows (which are unchanged:
                    // the acceptance driver targets summary-* verbatim).
                    state.obj?.let { obj ->
                        val kindName = entry?.kind ?: resource
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (kindName in STATUS_KINDS) {
                                StatusPill(resolveStatus(obj))
                            }
                            val chips = conditionChips(obj)
                            if (chips.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(top = 8.dp)
                                        .testTag("condition-chips"),
                                ) {
                                    chips.forEach { StatusPill(it) }
                                }
                            }
                            val created = (obj["metadata"] as? Map<*, *>)?.get("creationTimestamp") as? String
                            MetaGrid(
                                rows = buildList {
                                    add("kind" to kindName)
                                    add("api" to apiVersion)
                                    add(
                                        "created" to (
                                            created?.let { c ->
                                                runCatching { Instant.parse(c) }.getOrNull()
                                                    ?.let(RelativeTime::ago)
                                            } ?: created ?: "—"
                                            ),
                                    )
                                },
                                columns = 3,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .testTag("detail-metagrid"),
                            )
                            TextButton(
                                onClick = {
                                    viewModel.ensureYaml()
                                    yamlSheetOpen = true
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag("yaml-sheet-open"),
                            ) { Text("YAML sheet") }
                        }
                    }
                    HorizontalDivider()
                    val rows = state.obj?.let { SummaryRows.build(it) }.orEmpty()
                    rows.forEach { (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("summary-$k"),
                        ) {
                            Text(
                                k,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp).let { m ->
                                    m then Modifier.fillMaxWidth(0.35f)
                                },
                            )
                            Text(v, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }

            // spec 06 "Delete flow": shared confirmation dialog + error banner.
            if (state.confirmDelete) {
                DeleteConfirmDialog(
                    kind = entry?.kind ?: "resource",
                    name = name,
                    namespace = namespace,
                    caution = entry?.kind?.let { ResourceCatalog.cascadeCaution(it) },
                    deleting = state.deleting,
                    onConfirm = { viewModel.performDelete() },
                    onDismiss = { viewModel.dismissDelete() },
                )
            }

            // spec 12 goal 2 (Q4): YAML quick-peek bottom sheet with a
            // line-number gutter (mobile-for-argocd details parity); the
            // full-screen YAML tab remains the primary surface.
            if (yamlSheetOpen) {
                ModalBottomSheet(onDismissRequest = { yamlSheetOpen = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp)
                            .testTag("yaml-sheet"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "YAML",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            state.yaml?.let { y ->
                                TextButton(
                                    onClick = { clipboard.setText(AnnotatedString(y)) },
                                    modifier = Modifier.testTag("copy-yaml-sheet"),
                                ) { Text("Copy") }
                            }
                        }
                        val yaml = state.yaml
                        if (yaml == null) {
                            Text("—", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val lineCount = yaml.lineSequence().count()
                            // single scroll container so gutter and body stay
                            // in sync (and both remain in the semantics tree)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    (1..lineCount).joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("yaml-sheet-gutter"),
                                )
                                Text(
                                    yaml,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // spec 06 "States": conflict never silently overwrites — reload or keep editing.
            if (state.showConflict) {
                AlertDialog(
                    onDismissRequest = { viewModel.conflictKeepEditing() },
                    modifier = Modifier.testTag("conflict-dialog"),
                    title = { Text("Conflict") },
                    text = {
                        Text("This ${entry?.kind ?: "resource"} changed in the cluster since you " +
                            "opened it. Reload it and re-apply your edit; nothing was overwritten.")
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.conflictReload() },
                            modifier = Modifier.testTag("conflict-reload")) { Text("Reload") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.conflictKeepEditing() },
                            modifier = Modifier.testTag("conflict-keep")) { Text("Keep editing") }
                    },
                )
            }

            // spec 06 "Edit flow": dirty-state exit confirmation.
            if (showDiscardDialog) {
                AlertDialog(
                    onDismissRequest = { showDiscardDialog = false },
                    modifier = Modifier.testTag("discard-dialog"),
                    title = { Text("Discard changes?") },
                    text = { Text("Your edits will be lost.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDiscardDialog = false
                            viewModel.exitEdit()
                        }, modifier = Modifier.testTag("discard-confirm")) { Text("Discard") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDiscardDialog = false },
                            modifier = Modifier.testTag("discard-keep")) { Text("Keep editing") }
                    },
                )
            }
        }
    }
}
