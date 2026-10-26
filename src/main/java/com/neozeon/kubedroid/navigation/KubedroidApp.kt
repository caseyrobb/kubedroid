package com.neozeon.kubedroid.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.neozeon.kubedroid.ui.cluster.ClusterListScreen
import com.neozeon.kubedroid.ui.logs.LogScreen
import com.neozeon.kubedroid.ui.resources.BrowseScreen
import com.neozeon.kubedroid.ui.resources.ResourceDetailScreen
import com.neozeon.kubedroid.ui.resources.ResourceScene

/**
 * Pops one entry from the app-owned back stack.
 * No-op at the root: single-entry stacks are left intact so system back
 * exits the app cleanly instead of emptying the stack.
 * Pure function — unit-tested in BackStackTest.
 */
fun popBackStack(backStack: MutableList<NavKey>) {
    if (backStack.size > 1) {
        // Not removeLast(): on JDK 21 toolchains that resolves to
        // java.util.List.removeLast() (API 35+) and would crash on minSdk 26.
        backStack.removeAt(backStack.lastIndex)
    }
}

@Composable
fun KubedroidApp() {
    val backStack = rememberNavBackStack(HomeKey)

    NavDisplay(
        backStack = backStack,
        onBack = { popBackStack(backStack) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeKey> {
                // spec 03: placeholder body replaced by the cluster list
                // spec 04 stage 1: row tap activates AND browses (amendment)
                ClusterListScreen(
                    onBrowse = { id -> backStack.add(BrowseKey(id)) },
                )
            }
            entry<BrowseKey> { key ->
                BrowseScreen(
                    connectionId = key.connectionId,
                    onBack = { popBackStack(backStack) },
                    onOpenType = { backStack.add(it) },
                )
            }
            entry<ResourceListKey> { key ->
                ResourceScene(
                    connectionId = key.connectionId,
                    apiVersion = key.apiVersion,
                    resource = key.resource,
                    onBack = { popBackStack(backStack) },
                    onOpenDetailStacked = { backStack.add(it) },
                    // spec 05: Logs action from either detail surface opens the viewer.
                    onOpenLogs = { d ->
                        if (d.namespace != null) {
                            backStack.add(LogKey(d.connectionId, d.namespace, d.name, null))
                        }
                    },
                )
            }
            entry<ResourceDetailKey> { key ->
                ResourceDetailScreen(
                    connectionId = key.connectionId,
                    apiVersion = key.apiVersion,
                    resource = key.resource,
                    namespace = key.namespace,
                    name = key.name,
                    onBack = { popBackStack(backStack) },
                    onDeleted = { popBackStack(backStack) }, // spec 06; list refreshes via tick
                    onOpenLogs = {
                        if (key.namespace != null) {
                            backStack.add(LogKey(key.connectionId, key.namespace, key.name, null))
                        }
                    },
                )
            }
            entry<LogKey> { key ->
                LogScreen(
                    connectionId = key.connectionId,
                    namespace = key.namespace,
                    podName = key.podName,
                    container = key.container,
                    onBack = { popBackStack(backStack) },
                )
            }
        },
    )
}
