package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.neozeon.kubedroid.data.kubernetes.CatalogEntry
import com.neozeon.kubedroid.data.kubernetes.CatalogGroup
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import com.neozeon.kubedroid.navigation.ResourceListKey
import java.util.UUID

/**
 * Spec 04 "BrowseScreen": two-column grid of the frozen catalog grouped by
 * workload/network/config/storage/cluster. Tap opens the resource list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    connectionId: UUID,
    viewModel: BrowseViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenType: (ResourceListKey) -> Unit = {},
) {
    val connection by viewModel.connectionFor(connectionId).collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = connection
                            ?.let { "${it.displayName} · ${it.contextName}" }
                            ?: "Browse",
                        modifier = Modifier.testTag("browse-title"),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("browse-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("resource-grid"),
        ) {
            CatalogGroup.entries.forEach { group ->
                val groupEntries = ResourceCatalog.byGroup[group].orEmpty()
                if (groupEntries.isEmpty()) return@forEach
                item(span = { GridItemSpan(maxLineSpan) }, key = "group-${group.name}") {
                    Text(
                        group.display,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 2.dp)
                            .testTag("group-${group.name}"),
                    )
                }
                items(groupEntries, key = { "${it.apiVersion}/${it.resource}" }) { entry ->
                    TypeCard(entry) {
                        onOpenType(ResourceListKey(connectionId, entry.apiVersion, entry.resource))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeCard(entry: CatalogEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("type-${entry.apiVersion}/${entry.resource}")
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${entry.resource}.${entry.group.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
