package com.neozeon.kubedroid.ui.crd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 07 stage 2 Testing (Robolectric Compose): the CRD list screen —
 * rows, refresh re-fetch (Q1), client-side filter (Q5), empty state,
 * 403 copy naming customresourcedefinitions, row tap -> CR navigation,
 * and malformed CRDs never blanking the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CrdListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val widgetCrd =
        """{"metadata":{"name":"widgets.example.com"},
           "spec":{"group":"example.com","scope":"Namespaced",
             "names":{"plural":"widgets","kind":"Widget","shortNames":["wid"]},
             "versions":[{"name":"v1","served":true,"storage":true}]}}"""

    private val clusterCrd =
        """{"metadata":{"name":"clusters.stable.example.com"},
           "spec":{"group":"stable.example.com","scope":"Cluster",
             "names":{"plural":"clusters","kind":"Cluster"},
             "versions":[{"name":"v1","served":true},{"name":"v2","served":true,"storage":true}]}}"""

    private fun listJson() = """{"items":[$widgetCrd,{"garbage":true},$clusterCrd]}"""

    private fun harness(code: Int = 200, body: () -> String = ::listJson) =
        ResourceTestHarness({ _, _ -> code to body() })

    private fun vm(h: ResourceTestHarness) =
        CrdListViewModel(h.crdRepository, CoroutineScope(Dispatchers.Unconfined))

    private fun show(h: ResourceTestHarness, v: CrdListViewModel, onOpenCr: (String, String) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            KubedroidTheme {
                CrdListScreen(h.connectionId, viewModel = v, onOpenCr = onOpenCr)
            }
        }
    }

    @Test fun rowsRenderNameGroupKindAndScope() {
        val h = harness()
        show(h, vm(h))
        composeRule.onNodeWithTag("crd-widgets.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("example.com · Widget").assertIsDisplayed()
        // spec 08: CRD rows carry the official crd.svg (contentDescription = kind).
        composeRule.onNodeWithContentDescription("Widget").assertIsDisplayed()
        composeRule.onNodeWithText("1 served version · namespaced").assertIsDisplayed()
        composeRule.onNodeWithTag("crd-clusters.stable.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("2 served versions · cluster").assertIsDisplayed()
        // malformed entry skipped, list not blanked
        composeRule.onNodeWithText("No custom resources").assertDoesNotExist()
    }

    @Test fun refreshReFetchesPastTheCache() {
        val h = harness()
        val v = vm(h)
        show(h, v)
        val before = h.seen.size
        composeRule.onNodeWithTag("crd-refresh").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { h.seen.size > before }
        assertEquals(2, h.seen.size)
        assertEquals(2, h.seen.count { "customresourcedefinitions" in it })
    }

    @Test fun searchFiltersClientSideIncludingShortNames() {
        val h = harness()
        show(h, vm(h))
        composeRule.onNodeWithTag("crd-search").performTextInput("wid")
        composeRule.onNodeWithTag("crd-widgets.example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("crd-clusters.stable.example.com").assertDoesNotExist()
        composeRule.onNodeWithTag("crd-search").performTextInput("zzz")
        composeRule.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test fun emptyClusterShowsEmptyState() {
        val h = harness { """{"items":[]}""" }
        show(h, vm(h))
        composeRule.onNodeWithTag("crd-empty").assertIsDisplayed()
        composeRule.onNodeWithText("No custom resources").assertIsDisplayed()
    }

    @Test fun forbiddenNamesCustomResourceDefinitions() {
        val h = harness(code = 403) { """{"kind":"Status","code":403}""" }
        show(h, vm(h))
        composeRule.onNodeWithTag("crd-error").assertIsDisplayed()
        composeRule.onNodeWithText("customresourcedefinitions", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("crd-retry").assertIsDisplayed()
    }

    @Test fun rowTapOpensCrListWithDefaultVersion() {
        val h = harness()
        var opened: Pair<String, String>? = null
        show(h, vm(h), onOpenCr = { av, res -> opened = av to res })
        composeRule.onNodeWithTag("crd-clusters.stable.example.com").performClick()
        // Q2: default = storage version when served -> v2
        assertEquals("stable.example.com/v2" to "clusters", opened)
    }
}
