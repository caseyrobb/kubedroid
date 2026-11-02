package com.neozeon.kubedroid.ui.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec 12 goal 2: status pill renders label + content-desc from its token. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatusPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(token: StatusToken) = composeRule.setContent {
        KubedroidTheme { StatusPill(token) }
    }

    @Test fun podRunning() {
        show(PodStatus.RUNNING)
        composeRule.onNodeWithText("Running").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Running").assertIsDisplayed()
    }

    @Test fun pendingAmber() {
        show(PodStatus.PENDING)
        composeRule.onNodeWithText("Pending").assertIsDisplayed()
    }

    @Test fun workloadSuspended() {
        show(WorkloadStatus.SUSPENDED)
        composeRule.onNodeWithText("Suspended").assertIsDisplayed()
    }

    @Test fun probeUnknown() {
        show(ProbeStatus.UNVALIDATED)
        composeRule.onNodeWithText("Unknown").assertIsDisplayed()
    }
}
