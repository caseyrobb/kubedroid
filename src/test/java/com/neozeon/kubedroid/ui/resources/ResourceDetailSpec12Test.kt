package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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
 * Spec 12 stage 4 (Robolectric Compose): detail SUMMARY identity header
 * (pill + metadata grid) and the YAML quick-peek bottom sheet with a
 * line-number gutter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceDetailSpec12Test {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showDetail(h: ResourceTestHarness, resource: String = "pods") {
        val vm = ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = resource,
                    namespace = "web",
                    name = "web-1",
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun podSummaryShowsPillAndMetadataGrid() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1") })
        showDetail(h)
        composeRule.onNodeWithTag("detail-metagrid").assertIsDisplayed()
        composeRule.onNodeWithText("KIND").assertIsDisplayed()
        composeRule.onNodeWithText("CREATED").assertIsDisplayed()
        // Phase token renders as a pill; the summary status.phase row may
        // carry the same word, so assert existence rather than uniqueness.
        assertTrue(composeRule.onAllNodesWithTextSafe("Running"))
    }

    @Test fun conditionChipsRenderPerCondition() {
        val obj = """{"kind":"Pod","apiVersion":"v1","metadata":{"name":"web-1","namespace":"web",""" +
            """"creationTimestamp":"2026-01-01T00:00:00Z"},"status":{"phase":"Running",""" +
            """"conditions":[{"type":"Ready","status":"True"},{"type":"PodScheduled","status":"False"}]}}"""
        val h = ResourceTestHarness({ _, _ -> 200 to obj })
        showDetail(h)
        assertTrue(tagExists("condition-chips"))
        // the pill exposes its label as contentDescription (merged
        // semantics), which is the stable query target for pills.
        assertTrue(contentDescExists("Ready"))
        assertTrue(contentDescExists("PodScheduled"))
    }

    @Test fun yamlSheetOpensWithLineNumberGutter() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.pod("web-1") })
        showDetail(h)
        composeRule.onNodeWithTag("yaml-sheet-open").performClick()
        composeRule.waitForIdle()
        assertTrue(tagExists("yaml-sheet"))
        assertTrue(tagExists("yaml-sheet-gutter"))
        // gutter starts at line 1 and body renders the document
        composeRule.onNodeWithTag("yaml-sheet-gutter").assertTextStartsWith("1")
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
    }

    private fun tagExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun contentDescExists(desc: String): Boolean =
        composeRule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider
        .onAllNodesWithTextSafe(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextStartsWith(
        prefix: String,
    ) {
        val texts = fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        org.junit.Assert.assertTrue(
            "gutter text ${texts.map { it.text }}",
            texts.any { it.text.startsWith(prefix) },
        )
    }
}
