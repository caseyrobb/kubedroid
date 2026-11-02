package com.neozeon.kubedroid.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec 12 goal 4: micro-label / monospace-value metadata grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MetaGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun labelsAreUppercaseValuesRender() {
        composeRule.setContent {
            KubedroidTheme {
                MetaGrid(
                    listOf("version" to "v1.36.4", "added" to "5h ago"),
                    columns = 2,
                )
            }
        }
        composeRule.onNodeWithTag("meta-label-version")
            .assertIsDisplayed()
            .assertTextEquals("VERSION")
        composeRule.onNodeWithTag("meta-value-version")
            .assertIsDisplayed()
            .assertTextEquals("v1.36.4")
        composeRule.onNodeWithText("ADDED").assertIsDisplayed()
        composeRule.onNodeWithText("5h ago").assertIsDisplayed()
    }

    @Test fun trailingShortRowDoesNotCrash() {
        composeRule.setContent {
            KubedroidTheme {
                MetaGrid(
                    listOf(
                        "a" to "1", "b" to "2", "c" to "3",
                        "d" to "4", "e" to "5",
                    ),
                    columns = 3,
                )
            }
        }
        composeRule.onNodeWithText("E").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEquals(
        expected: String,
    ) {
        val node = fetchSemanticsNode()
        val texts = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        org.junit.Assert.assertEquals("node ${node.id} text", listOf(expected), texts.map { it.text })
    }
}
