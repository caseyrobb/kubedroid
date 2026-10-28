package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 04 stage 4 Testing (Robolectric Compose): list rows render from the
 * canned gateway, search filters, the 403 escape hatch appears, empty and
 * end-of-list markers show. Infinite scroll itself is covered at the
 * ViewModel level (ResourceViewModelTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun listVm(h: ResourceTestHarness) =
        ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))

    @Test fun rowsRenderPrimaryAndSecondary() {
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
            else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1", app = "frontend")))
        })
        var opened: com.neozeon.kubedroid.navigation.ResourceDetailKey? = null
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    viewModel = listVm(h),
                    onOpenDetail = { opened = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pods").assertIsDisplayed() // top bar title
        composeRule.onNodeWithText("web-1").assertIsDisplayed()
        composeRule.onNodeWithText("Running").assertIsDisplayed()
        composeRule.onNodeWithTag("list-end").assertIsDisplayed() // single page, no token
        composeRule.onNodeWithTag("row-web-1").performClick()
        assertEquals("web-1", opened?.name)
        assertEquals("web", opened?.namespace)
    }

    @Test fun searchFiltersRows() {
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
            else 200 to ResourceFixtures.itemsJson(
                listOf(ResourceFixtures.pod("alpha", app = "web"), ResourceFixtures.pod("beta", app = "db")),
            )
        })
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("alpha").assertIsDisplayed()
        composeRule.onNodeWithTag("search").performTextInput("bet")
        composeRule.onNodeWithText("beta").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("alpha").fetchSemanticsNodes().isEmpty())
    }

    @Test fun forbiddenShowsErrorAndNamespaceHint() {
        val h = ResourceTestHarness({ _, _ -> 403 to "{}" })
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "secrets",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("list-error").assertIsDisplayed()
        composeRule.onNodeWithTag("forbidden-hint").assertIsDisplayed()
        composeRule.onNodeWithTag("retry").assertIsDisplayed()
    }

    @Test fun emptyListShowsEmptyState() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(emptyList()) })
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("empty-state").assertIsDisplayed()
    }

    // ---- spec 08 stage 2: official kind icons on list surfaces ----

    @Test fun rowsShowOfficialKindIcon() {
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
            else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1", app = "frontend")))
        })
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        // Leading slot icon per row, contentDescription = kind (Q3A: art keeps
        // its official colors; we only assert presence, not pixels).
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Pod").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test fun emptyStateShowsOfficialKindIcon() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.itemsJson(emptyList()) })
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("empty-icon").assertIsDisplayed()
    }
}
