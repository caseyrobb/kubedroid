package com.neozeon.kubedroid.ui.resources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.testing.ResourceFixtures
import com.neozeon.kubedroid.testing.ResourceTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 06 stage 2 Testing: the edit flow on ResourceDetailScreen/ViewModel —
 * sanitizer-seeded draft, local validation that NEVER hits the network,
 * identity guard, resourceVersion precondition on the wire, conflict dialog,
 * save-error Retry (same body, Q6), dirty-exit confirmation, and the
 * Secret-reveal-before-edit rule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResourceDetailEditTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun vm(h: ResourceTestHarness) =
        ResourceDetailViewModel(h.repository, CoroutineScope(Dispatchers.Unconfined))

    private fun ref(h: ResourceTestHarness) =
        ResourceRef(h.connectionId, "v1", "pods", "web", "web-1")

    private fun bodyOf(req: Request): String {
        val buf = Buffer()
        req.body?.writeTo(buf)
        return buf.readUtf8()
    }

    private fun harnessWith(
        podJson: String = ResourceFixtures.pod("web-1", rv = "123"),
        putResponse: (attempt: Int) -> Pair<Int, String> = { 200 to """{"metadata":{"name":"web-1"}}""" },
    ): Pair<ResourceTestHarness, () -> Int> {
        var putCount = 0
        val h = ResourceTestHarness(
            { _, _ -> 200 to podJson },
            requestHandler = { req ->
                if (req.method == "PUT") { putCount++; putResponse(putCount) } else 200 to podJson
            },
        )
        return h to { putCount }
    }

    // ---------------- ViewModel level ----------------

    @Test fun startEditSeedsDraftWithoutStatusOrResourceVersion() {
        val (h, _) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        val st = v.state.value
        assertTrue("must be editing", st.editing)
        assertFalse("status stripped from draft", st.draft.contains("status:"))
        assertFalse("resourceVersion stripped from draft", st.draft.contains("resourceVersion"))
        assertTrue("user-owned fields present", st.draft.contains("name: web-1"))
        assertTrue("edit forces YAML tab", st.tab == ResourceDetailViewModel.TAB_YAML)
    }

    @Test fun saveSendsLoadedResourceVersionAndDropsPastedStatus() {
        val (h, putCount) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.updateDraft(v.state.value.draft + "\nstatus:\n  phase: Succeeded\n")
        v.save()
        assertEquals(1, putCount())
        val put = h.seenRequests.single { it.method == "PUT" }
        val body = bodyOf(put)
        assertTrue("loaded RV is the precondition: $body", body.contains("\"resourceVersion\":\"123\""))
        assertFalse("pasted status dropped before PUT: $body", body.contains("\"status\""))
        assertFalse("edit exited on success", v.state.value.editing)
    }

    @Test fun invalidYamlBlocksNetworkEntirely() {
        val (h, putCount) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.updateDraft("spec:\n  bad: [unclosed\n")
        v.save()
        assertEquals("validation failure must never reach the network", 0, putCount())
        assertNotNull("inline validation message", v.state.value.validationError)
        assertTrue(v.state.value.validationError!!.contains("parse", ignoreCase = true))
    }

    @Test fun missingRequiredFieldsBlockedLocally() {
        val (h, putCount) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.updateDraft("apiVersion: v1\nkind: Pod\n") // no metadata
        v.save()
        assertEquals(0, putCount())
        assertTrue(v.state.value.validationError!!.contains("metadata"))
    }

    @Test fun identityGuardBlocksRenamingTheObject() {
        val (h, putCount) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.updateDraft(v.state.value.draft.replace("name: web-1", "name: web-2"))
        v.save()
        assertEquals("identity mismatch must block the PUT", 0, putCount())
        assertTrue(v.state.value.validationError!!.contains("metadata.name"))
    }

    @Test fun conflictShowsDialogAndKeepsDraft() {
        val (h, _) = harnessWith(
            putResponse = { 409 to """{"kind":"Status","message":"modified","code":409}""" },
        )
        val v = vm(h); v.open(ref(h)); v.startEdit()
        val edited = v.state.value.draft + "\n"
        v.updateDraft(edited)
        v.save()
        val st = v.state.value
        assertTrue("conflict dialog flagged", st.showConflict)
        assertTrue("still editing, draft kept", st.editing && st.draft == edited)
        v.conflictKeepEditing()
        assertFalse("dialog dismissed", v.state.value.showConflict)
        assertTrue("draft still there", v.state.value.editing)
    }

    @Test fun saveErrorRetryReissuesSameBody() {
        val bodies = mutableListOf<String>()
        val h = ResourceTestHarness(
            { _, _ -> 200 to ResourceFixtures.pod("web-1", rv = "123") },
            requestHandler = { req ->
                if (req.method == "PUT") { bodies += bodyOf(req); 500 to "boom" } else 200 to ResourceFixtures.pod("web-1", rv = "123")
            },
        )
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.updateDraft(v.state.value.draft.replace("nginx", "nginx:1.27"))
        v.save()
        assertTrue("save error surfaced", v.state.value.saveError is ResourceError.Server)
        assertTrue("draft survives the error", v.state.value.editing)
        v.save() // Retry as-is (Q6)
        assertEquals(2, bodies.size)
        assertEquals("retry re-PUTs the identical body", bodies[0], bodies[1])
    }

    @Test fun saveSuccessReloadsDetail() {
        val (h, _) = harnessWith()
        val v = vm(h); v.open(ref(h)); v.startEdit()
        v.save()
        assertFalse(v.state.value.editing)
        val gets = h.seenRequests.count { it.method == "GET" }
        assertEquals("GET, then reload after successful PUT", 2, gets)
    }

    // ---------------- Compose level ----------------

    private fun setDetail(h: ResourceTestHarness, onBack: () -> Unit = {}) {
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "pods",
                    namespace = "web",
                    name = "web-1",
                    viewModel = vm(h),
                    onBack = onBack,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun editModeSwapsTopBarAndShowsEditor() {
        val (h, _) = harnessWith()
        setDetail(h)
        composeRule.onNodeWithTag("edit-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yaml-editor").assertIsDisplayed()
        composeRule.onNodeWithTag("save-action").assertIsDisplayed()
        // copy/logs affordances are hidden while editing
        assertTrue(composeRule.onAllNodesWithTextLocal("Copy").isEmpty())
    }

    // small local helper: existence check without hamcrest/assertExists (see skill)
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextLocal(t: String) =
        onAllNodesWithText(t).fetchSemanticsNodes()

    @Test fun invalidDraftRendersInlineValidationAndBlocksSave() {
        val (h, putCount) = harnessWith()
        setDetail(h)
        composeRule.onNodeWithTag("edit-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yaml-editor").performTextInput("\nspec:\n  bad: [unclosed\n")
        composeRule.onNodeWithTag("save-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yaml-validation").assertIsDisplayed()
        assertEquals(0, putCount())
    }

    @Test fun dirtyCancelAsksDiscardThenExits() {
        val (h, _) = harnessWith()
        setDetail(h)
        composeRule.onNodeWithTag("edit-action").performClick()
        composeRule.waitForIdle()
        // clean cancel exits straight away
        composeRule.onNodeWithTag("cancel-edit").performClick()
        composeRule.waitForIdle()
        // dirty cancel asks first
        composeRule.onNodeWithTag("edit-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yaml-editor").performTextInput("# touched\n")
        composeRule.onNodeWithTag("cancel-edit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("discard-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("discard-confirm").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTagLocal("yaml-editor").isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagLocal(t: String) =
        onAllNodesWithTag(t).fetchSemanticsNodes()

    @Test fun cleanCancelExitsWithoutDialog() {
        val (h, _) = harnessWith()
        setDetail(h)
        composeRule.onNodeWithTag("edit-action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cancel-edit").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTagLocal("discard-dialog").isEmpty())
        composeRule.onNodeWithTag("edit-action").assertIsDisplayed()
    }

    @Test fun secretEditHiddenUntilRevealed() {
        val h = ResourceTestHarness({ _, _ -> 200 to ResourceFixtures.secret() })
        composeRule.setContent {
            KubedroidTheme {
                ResourceDetailScreen(
                    connectionId = h.connectionId,
                    apiVersion = "v1",
                    resource = "secrets",
                    namespace = "web",
                    name = "db",
                    viewModel = vm(h),
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue("no Edit while masked", composeRule.onAllNodesWithTagLocal("edit-action").isEmpty())
        composeRule.onNodeWithTag("reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("edit-action").assertIsDisplayed()
    }
}
