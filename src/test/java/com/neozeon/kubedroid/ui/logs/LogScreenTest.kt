package com.neozeon.kubedroid.ui.logs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 05 stage 1 (nav round trip): the placeholder viewer renders its
 * identity and back gesture fires; stage 4 replaces the body and extends
 * coverage with streaming states.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LogScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun placeholderRendersPodIdentityAndBackFires() {
        var backed = 0
        composeRule.setContent {
            KubedroidTheme {
                LogScreen(namespace = "web", podName = "web-1", container = "sidecar",
                    onBack = { backed++ })
            }
        }
        composeRule.onNodeWithTag("log-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("log-title").assertIsDisplayed()
        composeRule.onNodeWithText("web-1 · sidecar").assertIsDisplayed()
        composeRule.onNodeWithText("Logs for web/web-1").assertIsDisplayed()
        composeRule.onNodeWithTag("log-back").performClick()
        assertTrue("Back must fire exactly once", backed == 1)
    }
}
