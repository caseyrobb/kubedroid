package com.neozeon.kubedroid.ui.logs

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.testing.LogTestHarness
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Spec 05 stage 4 Testing: the viewer UI against the manually-driven fake
 * repository (LogTestHarness). Tags per spec: log-body, log-line, log-filter,
 * container-select, follow-toggle, save-button, retry-button, jump-pill,
 * ended-banner, stream-error; Robolectric asserts the Save click fires
 * ACTION_CREATE_DOCUMENT with text/plain and the filename template.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LogScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: LogTestHarness

    @Before fun setUp() {
        harness = LogTestHarness(File(tmp.root, "harness"))
        LogNavPayloads.clear()
    }

    @After fun tearDown() = LogNavPayloads.clear()

    private fun show(
        container: String? = null,
        known: Pair<List<String>, List<String>>? = listOf("app") to emptyList(),
        onBack: () -> Unit = {},
    ) {
        if (known != null) {
            LogNavPayloads.put(harness.connectionId, "web", "web-1", known)
        }
        val vm = harness.viewModel()
        composeRule.setContent {
            KubedroidTheme {
                LogScreen(
                    connectionId = harness.connectionId,
                    namespace = "web",
                    podName = "web-1",
                    container = container,
                    onBack = onBack,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun tailLinesRenderWithLineNumbers() {
        show()
        harness.emitLineLast("starting up")
        harness.emitLineLast("serving on :8080")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("log-body").assertIsDisplayed()
        composeRule.onAllNodesWithTag("log-line").assertCountEquals(2)
        composeRule.onNodeWithText("starting up").assertIsDisplayed()
        composeRule.onNodeWithText("serving on :8080").assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun timestampsHiddenByDefaultToggleReveals() {
        show()
        harness.emitLineLast("hello", ts = "2026-10-25T12:00:00Z")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("hello").assertIsDisplayed()
        composeRule.onNodeWithText("2026-10-25T12:00:00Z hello").assertDoesNotExist()

        composeRule.onNodeWithTag("timestamps-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2026-10-25T12:00:00Z hello").assertIsDisplayed()
        // Display-only: still exactly one stream request (spec 05).
        assertEquals(1, harness.streamRequests.size)
    }

    @Test fun filterNarrowsRows() {
        show()
        harness.emitLineLast("alpha one")
        harness.emitLineLast("beta two")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("log-filter").performTextInput("bet")
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("log-line").assertCountEquals(1)
        composeRule.onNodeWithText("beta two").assertIsDisplayed()
    }

    @Test fun errorCardNamesPodsLogAndRetryRestarts() {
        show()
        harness.emitErrorLast(ResourceError.ForbiddenLogs("web"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("stream-error").assertIsDisplayed()
        composeRule.onNodeWithText("pods/log", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("retry-button").performClick()
        composeRule.waitForIdle()
        assertEquals(2, harness.streamRequests.size)
        composeRule.onNodeWithTag("log-loading").assertIsDisplayed()
    }

    @Test fun emptyStreamRendersNoLogs() {
        show()
        harness.emitCompletedLast()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No logs").assertIsDisplayed()
    }

    @Test fun endedBannerAfterLines() {
        show()
        harness.emitLineLast("bye")
        harness.emitCompletedLast()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ended-banner").assertIsDisplayed()
        composeRule.onNodeWithText("Stream closed").assertIsDisplayed()
    }

    @Test fun containerDropdownHiddenForSingleContainer() {
        show()
        composeRule.onNodeWithTag("container-select").assertDoesNotExist()
    }

    @Test fun containerDropdownSwitchesStream() {
        show(known = listOf("app", "sidecar") to emptyList())
        composeRule.onNodeWithTag("container-select").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("sidecar").performClick()
        composeRule.waitForIdle()

        assertEquals("sidecar", harness.streamRequests.last().container)
        assertEquals(2, harness.streamRequests.size)
    }

    @Test fun followToggleReopensStreamWithFollow() {
        show()
        composeRule.onNodeWithTag("follow-toggle").performClick()
        composeRule.waitForIdle()
        assertTrue(harness.streamRequests.last().follow)
        assertEquals(2, harness.streamRequests.size)
    }

    @Test fun previousToggleReopensStreamWithPrevious() {
        show()
        composeRule.onNodeWithTag("previous-toggle").performClick()
        composeRule.waitForIdle()
        assertTrue(harness.streamRequests.last().previous)
    }

    @Test fun saveClickFiresCreateDocumentWithTemplate() {
        show()
        composeRule.onNodeWithTag("save-button").performClick()
        composeRule.waitForIdle()

        val activity: Activity = composeRule.activity
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent?.action)
        assertEquals("text/plain", intent?.type)
        val title = intent?.getStringExtra(Intent.EXTRA_TITLE) ?: ""
        assertTrue("filename: $title",
            Regex("""web_web-1_app_\d{8}-\d{6}\.log""").matches(title))
        // Save re-fetches server-side; the on-screen stream is untouched.
        assertEquals(1, harness.streamRequests.size)
    }

    @Test fun saveClickDoesNotTouchStreamUntilResult() {
        show()
        composeRule.onNodeWithTag("save-button").performClick()
        composeRule.waitForIdle()
        // No document result delivered yet: repository.save has not run.
        assertEquals(0, harness.saveCalls)
    }

    @Test fun backButtonFires() {
        var back = 0
        show(onBack = { back++ })
        composeRule.onNodeWithTag("log-back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, back)
    }

    @Test fun titleShowsPodAndNamespace() {
        show()
        // Existence check: the narrow Robolectric viewport can clip the
        // title behind the action row; the text itself must be present.
        assertTrue(composeRule.onAllNodesWithTag("log-title").fetchSemanticsNodes().isNotEmpty())
        assertTrue("title text",
            composeRule.onAllNodesWithText("web-1 · web").fetchSemanticsNodes().isNotEmpty())
    }
}
