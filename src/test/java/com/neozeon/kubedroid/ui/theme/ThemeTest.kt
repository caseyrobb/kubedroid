package com.neozeon.kubedroid.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(dark: Boolean) {
        composeRule.setContent {
            // dynamicColor=false: exercises the app's own seed palettes.
            KubedroidTheme(darkTheme = dark, dynamicColor = false) {
                Text("themed")
            }
        }
        composeRule.onNodeWithText("themed").assertIsDisplayed()
    }

    @Test
    fun lightSchemeComposes() = render(dark = false)

    @Test
    fun darkSchemeComposes() = render(dark = true)
}
