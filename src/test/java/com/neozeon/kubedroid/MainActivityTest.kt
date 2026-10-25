package com.neozeon.kubedroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MainActivityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun launchesAndShowsHomeScaffold() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            composeRule.onNodeWithText("kubedroid").assertIsDisplayed()
            composeRule.onNodeWithText("No cluster connected").assertIsDisplayed()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        } finally {
            scenario.close()
        }
    }
}
