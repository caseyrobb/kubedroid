package com.neozeon.kubedroid.ui.logs

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Spec 05 stage 1 placeholder: proves the `Logs` entry gesture and the
 * LogKey round-trip. Stage 4 replaces the body with the streaming viewer
 * (top bar controls, filter, log body, states, SAF save flow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    namespace: String,
    podName: String,
    container: String?,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag("log-screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        container?.let { "$podName · $it" } ?: podName,
                        modifier = Modifier.testTag("log-title"),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("log-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Logs for $namespace/$podName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("log-placeholder"),
            )
        }
    }
}
