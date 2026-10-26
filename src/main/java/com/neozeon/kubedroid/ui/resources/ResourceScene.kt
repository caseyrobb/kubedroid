package com.neozeon.kubedroid.ui.resources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.window.core.layout.WindowSizeClass
import com.neozeon.kubedroid.navigation.ResourceDetailKey
import com.neozeon.kubedroid.ui.adaptive.currentKubedroidWindowSize
import java.util.UUID

/**
 * Spec 04 "Navigation and entry" (adaptive): compact windows run the classic
 * single-pane stack; medium/expanded windows render the list-detail Scene
 * strategy — ResourceListScreen and ResourceDetailScreen side by side with
 * one shared selection state, so the detail never duplicates on the back
 * stack and each screen keeps exactly one ViewModel instance.
 */
@Composable
fun ResourceScene(
    connectionId: UUID,
    apiVersion: String,
    resource: String,
    onBack: () -> Unit,
    onOpenDetailStacked: (ResourceDetailKey) -> Unit,
    onOpenLogs: (ResourceDetailKey) -> Unit = {},
    windowSizeClass: WindowSizeClass = currentKubedroidWindowSize(),
    listViewModel: @Composable () -> ResourceListViewModel = { hiltViewModel() },
    detailViewModel: @Composable (ResourceDetailKey) -> ResourceDetailViewModel = { hiltViewModel() },
) {
    if (!useTwoPane(windowSizeClass)) {
        ResourceListScreen(
            connectionId = connectionId,
            apiVersion = apiVersion,
            resource = resource,
            viewModel = listViewModel(),
            onBack = onBack,
            onOpenDetail = onOpenDetailStacked,
        )
        return
    }

    var selected by remember { mutableStateOf<ResourceDetailKey?>(null) }
    Row(modifier = Modifier.fillMaxSize().testTag("two-pane-scene")) {
        ResourceListScreen(
            connectionId = connectionId,
            apiVersion = apiVersion,
            resource = resource,
            viewModel = listViewModel(),
            onBack = onBack,
            onOpenDetail = { selected = it },
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        )
        VerticalDivider()
        val detail = selected
        if (detail != null) {
            ResourceDetailScreen(
                connectionId = detail.connectionId,
                apiVersion = detail.apiVersion,
                resource = detail.resource,
                namespace = detail.namespace,
                name = detail.name,
                viewModel = detailViewModel(detail),
                onBack = { selected = null },
                onOpenLogs = { onOpenLogs(detail) },
                onDeleted = { selected = null }, // lists refresh via ResourceInvalidations
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .testTag("detail-placeholder"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Select a resource",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Medium (>=600dp) and expanded windows get the two-pane strategy (spec 02 classes). */
fun useTwoPane(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
