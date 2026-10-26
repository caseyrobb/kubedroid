package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 06 stage 3 Testing: the delete flows — shared confirmation (simple
 * dialog, cascade caution lines), 404-as-success info, RBAC verb copy, and
 * post-mutation list invalidation via the shared tick.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun detailVm(h: ResourceTestHarness) =
        ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))

    private fun listVm(h: ResourceTestHarness) =
        ResourceListViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))

    private fun podHandler(delete: Pair<Int, String> = 200 to """{"kind":"Status","status":"Success"}""") =
        ResourceTestHarness(
            { _, _ -> 200 to ResourceFixtures.pod("web-1") },
            requestHandler = { req ->
                if (req.method == "DELETE") delete else 200 to ResourceFixtures.pod("web-1")
            },
        )

    // ---------------- detail delete ----------------

    @Test fun confirmThenDeleteIncrementsDeletedEventAndBumpsTick() {
        val h = podHandler()
        val tickBefore = ResourceInvalidations.tick.value
        val v = detailVm(h)
        v.open(ResourceRef(h.connectionId, "v1", "pods", "web", "web-1"))
        v.requestDelete()
        assertTrue(v.state.value.confirmDelete)
        v.performDelete()
        assertEquals(1, v.state.value.deletedEvent)
        assertEquals("invalidation tick bumped", tickBefore + 1, ResourceInvalidations.tick.value)
        val del = h.seenRequests.single { it.method == "DELETE" }
        assertEquals("/api/v1/namespaces/web/pods/web-1", del.url.encodedPath)
    }

    @Test fun cancelKeepsObject() {
        val h = podHandler()
        val v = detailVm(h)
        v.open(ResourceRef(h.connectionId, "v1", "pods", "web", "web-1"))
        v.requestDelete()
        v.dismissDelete()
        assertFalse(v.state.value.confirmDelete)
        assertEquals(0, v.state.value.deletedEvent)
        assertTrue(h.seenRequests.none { it.method == "DELETE" })
    }

    @Test fun alreadyGoneIsSuccessWithInfo() {
        val h = podHandler(delete = 404 to """{"kind":"Status","reason":"NotFound","code":404}""")
        val v = detailVm(h)
        v.open(ResourceRef(h.connectionId, "v1", "pods", "web", "web-1"))
        v.requestDelete()
        v.performDelete()
        assertEquals(1, v.state.value.deletedEvent) // success, not error
        assertNotNull("info line about already-deleted", v.state.value.deleteInfo)
        assertTrue(v.state.value.deleteInfo!!.contains("already"))
    }

    @Test fun forbiddenNamesDeleteVerb() {
        val h = podHandler(delete = 403 to """{"kind":"Status","message":"nope","code":403}""")
        val v = detailVm(h)
        v.open(ResourceRef(h.connectionId, "v1", "pods", "web", "web-1"))
        v.requestDelete()
        v.performDelete()
        val err = v.state.value.deleteError
        assertTrue("expected ForbiddenVerb, got $err", err is ResourceError.ForbiddenVerb)
        assertEquals("delete", (err as ResourceError.ForbiddenVerb).verb)
        assertEquals(0, v.state.value.deletedEvent)
    }

    // ---------------- list delete + invalidation ----------------

    private fun listHarness(delete: Pair<Int, String> = 200 to ""): ResourceTestHarness =
        ResourceTestHarness(
            { path, _ ->
                if (path.endsWith("/namespaces")) 200 to ResourceFixtures.itemsJson(emptyList())
                else 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1")))
            },
            requestHandler = { req ->
                when {
                    req.method == "DELETE" -> delete
                    req.url.encodedPath.endsWith("/namespaces") ->
                        200 to ResourceFixtures.itemsJson(emptyList())
                    else -> 200 to ResourceFixtures.itemsJson(listOf(ResourceFixtures.pod("web-1")))
                }
            },
        )

    @Test fun listRowDeleteIssuesDeleteAndRefreshes() {
        val h = listHarness()
        val v = listVm(h)
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        val summary = v.state.value.items.first()
        val getsBefore = h.seenRequests.count { it.method == "GET" }
        v.requestDelete(summary)
        assertNotNull(v.state.value.deleteTarget)
        v.confirmDelete()
        assertEquals(1, h.seenRequests.count { it.method == "DELETE" })
        assertTrue("list refreshed via invalidation tick",
            h.seenRequests.count { it.method == "GET" } > getsBefore)
    }

    @Test fun listDeleteForbiddenKeepsTargetNullAndShowsError() {
        val h = listHarness(delete = 403 to """{"kind":"Status","message":"no","code":403}""")
        val v = listVm(h)
        v.open(ResourceListViewModel.Target(h.connectionId, "v1", "pods"))
        v.requestDelete(v.state.value.items.first())
        v.confirmDelete()
        assertTrue(v.state.value.deleteError is ResourceError.ForbiddenVerb)
        assertEquals(null, v.state.value.deleteTarget)
    }

    // ---------------- Compose ----------------

    @Test fun detailDeleteFlow_showsCautionAndCallsOnDeleted() {
        val h = podHandler()
        var deletedCalls = 0
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = detailVm(h),
                    onDeleted = { deletedCalls++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete-dialog").assertIsDisplayed()
        // Pod cascade caution from the catalog
        composeRule.onNodeWithText("terminated", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("delete-confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, h.seenRequests.count { it.method == "DELETE" })
        assertEquals("onDeleted fired after success", 1, deletedCalls)
    }

    @Test fun listRowOverflowDeleteFlow() {
        val h = listHarness()
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
        composeRule.onNodeWithTag("row-menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("row-delete").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-message").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, h.seenRequests.count { it.method == "DELETE" })
    }
}
