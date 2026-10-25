package com.neozeon.kubedroid.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.neozeon.kubedroid.ui.cluster.ClusterListScreen

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
                ClusterListScreen()
            }
        },
    )
}
