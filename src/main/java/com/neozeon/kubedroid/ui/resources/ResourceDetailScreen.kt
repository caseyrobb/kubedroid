package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import com.neozeon.kubedroid.data.model.ResourceRef
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
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val entry = ResourceCatalog.find(apiVersion, resource)
    val isSecret = entry?.kind == "Secret"
    val isPod = entry?.kind == "Pod"
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(connectionId, apiVersion, resource, namespace, name) {
        viewModel.open(ResourceRef(connectionId, apiVersion, resource, namespace, name))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(name, modifier = Modifier.testTag("detail-title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // spec 05 amendment: Pods only — Logs opens the viewer.
                    if (isPod && state.obj != null) {
                        TextButton(onClick = onOpenLogs, modifier = Modifier.testTag("open-logs")) {
                            Text("Logs")
                        }
                    }
                    if (isSecret && state.obj != null && !state.revealed) {
                        TextButton(onClick = { viewModel.reveal() }, modifier = Modifier.testTag("reveal")) {
                            Text("Reveal")
                        }
                    }
                    if (state.tab == ResourceDetailViewModel.TAB_YAML) {
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
                    onClick = { viewModel.selectTab(ResourceDetailViewModel.TAB_SUMMARY) },
                    text = { Text("Summary") },
                )
                Tab(
                    selected = state.tab == ResourceDetailViewModel.TAB_YAML,
                    onClick = { viewModel.selectTab(ResourceDetailViewModel.TAB_YAML) },
                    text = { Text("YAML") },
                )
            }

            val err = state.error
            when {
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
        }
    }
}
