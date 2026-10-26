package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.core.layout.WindowSizeClass
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 04 stage 5 Testing: the adaptive Scene strategy. Compact stays
 * single-pane; medium/expanded render list-detail side by side with the
 * detail rendered from the shared selection (no second back-stack entry).
 */
class UseTwoPaneTest {
    @Test fun compactIsSinglePane() {
        assertEquals(false, useTwoPane(WindowSizeClass(400, 800)))
    }

    @Test fun mediumAndExpandedAreTwoPane() {
        assertEquals(true, useTwoPane(WindowSizeClass(600, 800)))
        assertEquals(true, useTwoPane(WindowSizeClass(1200, 800)))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceSceneTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun harness() = ResourceTestHarness({ path, _ ->
        if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
        else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1")))
    })

    @Test fun compactStacksDetailOnBackstack() {
        val h = harness()
        val listVm = ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        val detailVm = ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        var stacked: com.neozeon.kubedroid.navigation.ResourceDetailKey? = null
        composeRule.setContent {
            KubedroidTheme {
                ResourceScene(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    onBack = {},
                    onOpenDetailStacked = { stacked = it },
                    windowSizeClass = WindowSizeClass(400, 800),
                    listViewModel = { listVm },
                    detailViewModel = { detailVm },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("row-web-1").performClick()
        assertEquals("web-1", stacked?.name) // compact: stacked navigation, no inline detail
        assertTrue(composeRule.onAllNodesWithTag("two-pane-scene").fetchSemanticsNodes().isEmpty())
    }

    @Test fun mediumRendersListDetailSideBySide() {
        val h = harness()
        val listVm = ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        val detailVm = ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            KubedroidTheme {
                ResourceScene(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    onBack = {},
                    onOpenDetailStacked = { error("two-pane must not stack") },
                    windowSizeClass = WindowSizeClass(800, 800),
                    listViewModel = { listVm },
                    detailViewModel = { detailVm },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("two-pane-scene").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-placeholder").assertIsDisplayed()
        composeRule.onNodeWithTag("row-web-1").performClick()
        composeRule.waitForIdle()
        // detail pane shows the selected pod (tag: the row shares the text);
        // list pane keeps its row too (existence, not isDisplayed: panes are
        // cramped at Robolectric's default 320dp width)
        composeRule.onNodeWithTag("detail-title").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("row-web-1").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
