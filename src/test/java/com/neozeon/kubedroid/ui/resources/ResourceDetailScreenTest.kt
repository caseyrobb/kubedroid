package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 04 stage 4/5 Testing (Robolectric Compose): Summary facts render,
 * YAML tab shows monospace document, Secrets stay redacted until Reveal.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun detailVm(h: ResourceTestHarness) =
        ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))

    @Test fun summaryTabShowsFactsAndStatus() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1", app = "frontend") })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-title").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-name").assertIsDisplayed()
        composeRule.onNodeWithText("app=frontend").assertIsDisplayed() // labels row
        composeRule.onNodeWithText("nginx").assertIsDisplayed()        // spec.containers[0].image
        // spec 07 (Q4) added apiVersion/kind/conditions rows — podIP can
        // land below the fold in a scrollable summary; assert existence.
        assertTrue(
            composeRule.onAllNodesWithText("10.42.0.7").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test fun yamlTabRendersDocumentAndCopyAction() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1") })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("YAML").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yaml-view").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-yaml").assertIsDisplayed()
    }

    @Test fun secretStaysRedactedUntilReveal() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.secret() })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "secrets",
                    namespace = "web",
                    name = "db",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("•••• (1 bytes)").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("eA==").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("eA==").assertIsDisplayed()
    }

    @Test fun notFoundShowsErrorMessage() {
        val h = ResourceTestHarness({ _, _ -> 404 to "{}" })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "gone",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-error").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-retry").assertIsDisplayed()
    }

    @Test fun podDetailShowsLogsActionAndInvokesCallback() {
        // spec 05 amendment (stage 1): Pods only — Logs opens the viewer.
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1") })
        var opened = 0
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = detailVm(h),
                    onOpenLogs = { opened++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open-logs").assertIsDisplayed()
        composeRule.onNodeWithTag("open-logs").performClick()
        assertTrue("Logs action must invoke onOpenLogs exactly once", opened == 1)
    }

    @Test fun logsActionAbsentForNonPodKinds() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.secret() })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "secrets",
                    namespace = "web",
                    name = "db",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("open-logs").fetchSemanticsNodes().isEmpty())
    }

    // ---- spec 08 stage 2: official kind icon in the detail header ----

    @Test fun headerShowsOfficialKindIcon() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1", app = "frontend") })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = detailVm(h),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pod").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-title").assertIsDisplayed()
    }
}
