package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 07 stage 3 Testing (Robolectric Compose): discovered CRs browsing —
 * dynamic entries resolve through CatalogResolver into the existing list/
 * detail screens, printer columns render (Q3), version chips switch (Q2),
 * the generic Summary fallback covers CRs (Q4), and undiscovered types
 * miss cleanly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CrdBrowsingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val widgetCrd =
        """{"metadata":{"name":"widgets.example.com"},
           "spec":{"group":"example.com","scope":"Namespaced",
             "names":{"plural":"widgets","kind":"Widget"},
             "versions":[{"name":"v1","served":true},
                         {"name":"v2","served":true,"storage":true,
                          "additionalPrinterColumns":[
                            {"name":"Replicas","type":"integer","jsonPath":"{.spec.replicas}"},
                            {"name":"Phase","type":"string","jsonPath":"{.status.phase}"}]}]}}"""

    private val widgetCr =
        """{"apiVersion":"example.com/v1","kind":"Widget",
           "metadata":{"name":"w1","namespace":"web","creationTimestamp":"2026-02-02T00:00:00Z"},
           "spec":{"replicas":5},
           "status":{"phase":"Active-7x","conditions":[
             {"type":"Ready","status":"True"},{"type":"Broken","status":"False"}]}}"""

    private fun harness() = ResourceTestHarness({ path, _ ->
        when {
            path.endsWith("customresourcedefinitions") -> 200 to """{"items":[$widgetCrd]}"""
            path.endsWith("namespaces") -> 200 to ResourceFixtures.itemsJson(emptyList())
            path.endsWith("/widgets/w1") -> 200 to widgetCr
            path.contains("/widgets") -> 200 to ResourceFixtures.itemsJson(listOf(widgetCr))
            else -> 404 to """{"kind":"Status","code":404}"""
        }
    })

    private fun seeded(h: ResourceTestHarness) {
        runBlocking { h.crdRepository.list(h.connectionId) } // discovery populates resolver
    }

    private fun listVm(h: ResourceTestHarness) =
        ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined), h.catalogResolver)

    private fun showList(h: ResourceTestHarness, apiVersion: String = "example.com/v2") {
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = apiVersion,
                    resource = "widgets",
                    viewModel = listVm(h),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun discoveredCrListRendersPrinterColumns() {
        val h = harness()
        seeded(h)
        showList(h)
        composeRule.onNodeWithTag("row-w1").assertIsDisplayed()
        // Q3: priority-0 columns from the selected (storage v2) version
        composeRule.onNodeWithText("5").assertIsDisplayed()
        composeRule.onNodeWithText("Active-7x").assertIsDisplayed()
        assertTrue(
            "expected the dynamic list path, saw ${h.seenRequests.map { it.url.encodedPath }}",
            h.seenRequests.any { it.url.encodedPath.contains("/apis/example.com/v2/widgets") },
        )
    }

    @Test fun versionChipsSwitchServedVersion() {
        val h = harness()
        seeded(h)
        showList(h)
        composeRule.onNodeWithTag("version-chips").assertIsDisplayed()
        composeRule.onNodeWithTag("version-v1").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "expected a v1 list request after chip tap, saw ${h.seenRequests.map { it.url.encodedPath }}",
            h.seenRequests.any { it.url.encodedPath.endsWith("/apis/example.com/v1/widgets") },
        )
    }

    @Test fun staticTypesShowNoChips() {
        val h = ResourceTestHarness({ path, _ ->
            if (path.endsWith("namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
            else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1")))
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
        composeRule.onNodeWithTag("version-chips").assertDoesNotExist()
    }

    @Test fun undiscoveredTypeSurfacesUnknownTypeError() {
        val h = harness()
        val vm = listVm(h)
        composeRule.setContent {
            KubedroidTheme {
                ResourceListScreen(
                    connectionId = h.connectionId,
                    apiVersion = "example.com/v2",
                    resource = "widgets",
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("list-error").assertIsDisplayed()
        composeRule.onNodeWithText("unknown resource type", substring = true).assertIsDisplayed()
    }

    @Test fun crDetailSummaryRendersFallbackFacts() {
        val h = harness()
        seeded(h)
        val vm = ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "example.com/v2",
                    resource = "widgets",
                    namespace = "web",
                    name = "w1",
                    viewModel = vm,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.obj != null }
        composeRule.waitForIdle()
        // Q4: generic fallback — header facts + True condition types.
        composeRule.onNodeWithTag("summary-kind").assertIsDisplayed()
        composeRule.onNodeWithText("Widget").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-apiVersion").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-conditions").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Ready").fetchSemanticsNodes().isNotEmpty(),
        ) // conditions row + flattened status.conditions[0].type
        assertTrue(vm.state.value.obj?.get("kind") == "Widget")
    }
}
