package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Spec 12 stage 3 (Robolectric Compose): pod-like lists render the health
 * bar, status chips, label chips, and row pills; chip taps take effect.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceListSpec12Test {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        h: ResourceTestHarness,
        resource: String,
    ): ResourceListViewModel {
        val vm = ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = resource,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        return vm
    }

    private fun podHarness(vararg pods: String) = ResourceTestHarness({ path, _ ->
        if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
        else 200 to ResourceFixtures.itemsJson(pods.toList())
    })

    @Test fun podListShowsHealthBarChipsAndPills() {
        val h = podHarness(
            ResourceFixtures.pod("good", app = "web"),
            ResourceFixtures.pod("bad", app = "web", phase = "Failed"),
        )
        show(h, "pods")
        composeRule.onNodeWithTag("health-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("health-segment-OK_GREEN").assertIsDisplayed()
        composeRule.onNodeWithTag("health-segment-FAILED_RED").assertIsDisplayed()
        composeRule.onNodeWithTag("status-chips").assertIsDisplayed()
        composeRule.onNodeWithTag("label-chips").assertIsDisplayed()
        composeRule.onNodeWithText("app=web").assertIsDisplayed()
        // Pill label per row (unique after the primary-text dedup).
        composeRule.onNodeWithText("Running").assertIsDisplayed()
        composeRule.onNodeWithText("Failed").assertIsDisplayed()
    }

    @Test fun failedChipFiltersRowsClientSide() {
        val h = podHarness(
            ResourceFixtures.pod("good", app = "web"),
            ResourceFixtures.pod("bad", app = "web", phase = "Failed"),
        )
        show(h, "pods")
        composeRule.onNodeWithTag("status-filter-FAILED").performClick()
        composeRule.onNodeWithText("good").assertDoesNotExist()
        composeRule.onNodeWithText("bad").assertIsDisplayed()
    }

    @Test fun labelChipTriggersServerSideSelector() {
        val h = podHarness(ResourceFixtures.pod("good", app = "web"))
        show(h, "pods")
        composeRule.onNodeWithTag("label-chip-app=web").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "expected labelSelector in ${h.seen}",
            h.seen.any { it.contains("labelSelector=app%3Dweb") },
        )
    }

    @Test fun nonStatusKindHidesHealthBarAndChips() {
        val cm = """{"kind":"ConfigMap","apiVersion":"v1","metadata":{"name":"cm-1",""" +
            """"namespace":"web","creationTimestamp":"2026-01-01T00:00:00Z"},"data":{"k":"v"}}"""
        val h = podHarness(cm)
        show(h, "configmaps")
        composeRule.onNodeWithText("cm-1").assertIsDisplayed()
        composeRule.onNodeWithTag("health-bar").assertDoesNotExist()
        composeRule.onNodeWithTag("status-chips").assertDoesNotExist()
    }
}
