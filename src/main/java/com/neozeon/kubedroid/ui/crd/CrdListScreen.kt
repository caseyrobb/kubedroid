package com.neozeon.kubedroid.ui.crd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.CrdDefinition
import com.neozeon.kubedroid.data.kubernetes.catalogIcon
import java.util.UUID

/**
 * Spec 07 "CrdListScreen": discovered CRDs for one connection — refresh
 * (Q1), client-side search (Q5), rows `name / group · Kind / N served ·
 * scope`; row tap opens the CR list (ResourceListKey — the CatalogResolver
 * makes the dynamic entry live). CRDs themselves are read-only
 * (Q6 human decision 2026-10-26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrdListScreen(
    connectionId: UUID,
    viewModel: CrdListViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenCr: (apiVersion: String, resource: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(connectionId) { viewModel.open(connectionId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Resources", modifier = Modifier.testTag("crd-list-title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("crd-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("crd-refresh")) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = { viewModel.onSearch(it) },
                placeholder = { Text("Filter by name or short name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("crd-search"),
            )
            val visible = state.visibleItems
            when {
                state.loading && visible.isEmpty() -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().testTag("crd-loading"),
                ) { CircularProgressIndicator() }

                state.error != null && visible.isEmpty() -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().testTag("crd-error-box"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!.userMessage(), modifier = Modifier.testTag("crd-error"))
                        Button(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("crd-retry")) {
                            Text("Retry")
                        }
                    }
                }

                visible.isEmpty() -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().testTag("crd-empty"),
                ) { Text(if (state.search.isBlank()) "No custom resources" else "No matches") }

                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize().testTag("crd-list"),
                ) {
                    items(visible, key = { it.name }) { crd ->
                        CrdRow(crd) { onOpenCr("${crd.group}/${crd.defaultVersion}", crd.plural) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrdRow(crd: CrdDefinition, onClick: () -> Unit) {
    val served = crd.servedVersions.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("crd-${crd.name}")
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        // spec 08: official icon (kind icon if it collides with a built-in,
        // crd.svg otherwise) — CRD rows resolve through catalogIcon too.
        Icon(
            painterResource(catalogIcon("${crd.group}/${crd.defaultVersion}", crd.kind)),
            contentDescription = crd.kind,
            modifier = Modifier
                .size(28.dp)
                .padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                crd.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${crd.group} · ${crd.kind}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "$served served version${if (served == 1) "" else "s"} · " +
                    if (crd.namespaced) "namespaced" else "cluster",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
