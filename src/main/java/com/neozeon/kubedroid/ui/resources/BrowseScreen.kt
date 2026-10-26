package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.util.UUID

/**
 * Spec 04 stage 1: resource-type grid destination. Placeholder body until
 * stages 2-4 land the catalog grid; shows the connection display name +
 * active context so the wiring from the cluster row is verifiable end-to-end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    connectionId: UUID,
    viewModel: BrowseViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
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
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Resource browsing lands here (spec 04, stages 2-4)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("browse-placeholder"),
            )
        }
    }
}
