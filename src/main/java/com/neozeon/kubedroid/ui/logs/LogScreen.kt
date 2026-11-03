package com.neozeon.kubedroid.ui.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neozeon.kubedroid.data.kubernetes.LogLine
import com.neozeon.kubedroid.ui.common.RelativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Spec 05 pod log viewer. Fixed 1,000-line tail, in-memory filter,
 * display-only Timestamps/Wrap toggles, Follow with auto-scroll + jump pill,
 * Previous (last terminated container), and Save via SAF (complete log, raw
 * server bytes). All state renderings carry the spec test tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    connectionId: UUID,
    namespace: String,
    podName: String,
    container: String?,
    onBack: () -> Unit = {},
    viewModel: LogViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(connectionId, namespace, podName, container) {
        viewModel.open(
            LogViewModel.Params(connectionId, namespace, podName),
            initialContainer = container,
            knownContainers = LogNavPayloads.take(connectionId, namespace, podName),
        )
    }

    // SAF save: ACTION_CREATE_DOCUMENT, text/plain, spec filename template.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        viewModel.save { uri?.let { context.contentResolver.openOutputStream(it) } }
    }
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val containerPart = ui.selectedContainer ?: container ?: "all"
        return "${namespace}_${podName}_${containerPart}_$stamp.log"
    }

    // Follow auto-scroll: pinned to bottom until the user scrolls up
    // (spec 05 "Jump to latest" pill). Detection is scroll-in-progress with
    // room left ahead; programmatic scrollToItem lands at the end
    // (canScrollForward=false), so it does not trip the pill.
    LaunchedEffect(listState, ui.follow) {
        if (!ui.follow) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress && listState.canScrollForward }
            .distinctUntilChanged()
            .collect { if (it) viewModel.onScrolledUp() }
    }
    LaunchedEffect(ui.lines.size, ui.follow, ui.autoScroll) {
        if (ui.follow && ui.autoScroll && ui.lines.isNotEmpty()) {
            listState.scrollToItem(ui.lines.size - 1)
        }
    }
    LaunchedEffect(ui.saveMessage) {
        ui.saveMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.testTag("log-screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("log-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        "$podName · $namespace",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("log-title"),
                    )
                },
                actions = {
                    if (ui.containers.size > 1) {
                        ContainerMenu(
                            selected = ui.selectedContainer,
                            containers = ui.containers,
                            onSelect = viewModel::selectContainer,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setFollow(!ui.follow) },
                        modifier = Modifier.testTag("follow-toggle"),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Follow",
                            tint = if (ui.follow) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Text toggles: the pinned material-icons set is core-only
                    // (spec 04 precedent — Copy is a TextButton for the same reason).
                    @Composable
                    fun tint(active: Boolean) =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    TextButton(
                        onClick = viewModel::toggleTimestamps,
                        modifier = Modifier.testTag("timestamps-toggle"),
                    ) {
                        Text("TS", color = tint(ui.showTimestamps))
                    }
                    TextButton(
                        onClick = { viewModel.setPrevious(!ui.previous) },
                        modifier = Modifier.testTag("previous-toggle"),
                    ) {
                        Text("Prev", color = tint(ui.previous))
                    }
                    TextButton(
                        onClick = viewModel::toggleWrap,
                        modifier = Modifier.testTag("wrap-toggle"),
                    ) {
                        Text("Wrap", color = tint(ui.wrap))
                    }
                    TextButton(
                        onClick = { saveLauncher.launch(suggestedFileName()) },
                        enabled = !ui.saving,
                        modifier = Modifier.testTag("save-button"),
                    ) {
                        Text(if (ui.saving) "Saving…" else "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = ui.filter,
                onValueChange = viewModel::onFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("log-filter"),
                placeholder = { Text("Filter logs") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            // spec 12 goal 8: relative age of the newest line (in the
            // content column — the TopAppBar title area is too cramped
            // beside five actions). A stalled follow stream is visible
            // at a glance.
            val age = RelativeTime.ago(RelativeTime.parse(ui.lines.lastOrNull()?.timestamp))
            if (age != "—") {
                Text(
                    "last line $age",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .testTag("log-age"),
                )
            }
            if (ui.evicted > 0) {
                Text(
                    "showing last ${LogBuffer.CAP} of ${ui.seen} lines",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .testTag("evicted-header"),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    ui.error != null && ui.lines.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .testTag("stream-error"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(ui.error!!.userMessage())
                        Button(onClick = viewModel::retry, modifier = Modifier.testTag("retry-button")) {
                            Text("Retry")
                        }
                    }
                    ui.loading && ui.lines.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.testTag("log-loading"))
                    }
                    ui.lines.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No logs",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("log-empty"),
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("log-body"),
                        ) {
                            // Mid-stream error: buffer is kept, banner + Retry
                            // (spec 05 manual-retry decision). Initial error
                            // with no lines renders the full card instead.
                            if (ui.error != null) {
                                item(key = "banner-error") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .testTag("stream-error"),
                                    ) {
                                        Text(
                                            ui.error!!.userMessage(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = viewModel::retry,
                                            modifier = Modifier.padding(start = 8.dp)
                                                .testTag("retry-button"),
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                            if (ui.ended) {
                                item(key = "banner-ended") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            "Stream closed",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("ended-banner"),
                                        )
                                        // spec 05: ended (Completed) shows muted
                                        // "stream closed" + Retry.
                                        TextButton(
                                            onClick = viewModel::retry,
                                            modifier = Modifier.padding(start = 8.dp)
                                                .testTag("ended-retry"),
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                            itemsIndexed(ui.visibleLines, key = { index, _ -> index }) { index, logLine ->
                                LogLineRow(index + 1, logLine,
                                    showTimestamps = ui.showTimestamps, wrap = ui.wrap)
                            }
                        }
                        if (ui.follow && !ui.autoScroll) {
                            Surface(
                                onClick = viewModel::jumpToLatest,
                                shape = MaterialTheme.shapes.extraLarge,
                                tonalElevation = 3.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                                    .testTag("jump-pill"),
                            ) {
                                Text(
                                    "Jump to latest",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerMenu(
    selected: String?,
    containers: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("container-select"),
        ) {
            Text(selected ?: "containers", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            containers.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(name)
                    },
                    modifier = Modifier.testTag("container-option-$name"),
                )
            }
        }
    }
}

@Composable
private fun LogLineRow(
    number: Int,
    logLine: LogLine,
    showTimestamps: Boolean,
    wrap: Boolean,
) {
    val prefix = if (showTimestamps && logLine.timestamp != null) "${logLine.timestamp} " else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log-line")
            .padding(horizontal = 12.dp, vertical = 1.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (wrap) {
            Text(
                prefix + logLine.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    prefix + logLine.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
