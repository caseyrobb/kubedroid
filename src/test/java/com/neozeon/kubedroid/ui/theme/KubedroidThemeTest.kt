package com.neozeon.kubedroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 08 stage 3 Testing (Q5A): the theme factory falls back to the fixed
 * KubernetesBlue-seeded palette when dynamic color is off/unavailable, and
 * the availability probe requires BOTH Android 12+ and the framework flag —
 * the GrapheneOS case (SDK >= S but no Material You) must land on the seed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KubedroidThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun dynamicSupported_requiresSdkS_and_frameworkFlag() {
        assertFalse(KubedroidPalette.dynamicSupported(30, true))   // pre-S: never
        assertFalse(KubedroidPalette.dynamicSupported(34, false))  // GrapheneOS shape
        assertTrue(KubedroidPalette.dynamicSupported(34, true))    // stock 12+
    }

    @Test fun probeDoesNotCrash_whenFrameworkBoolAbsent() {
        // Robolectric's android-all may or may not ship the bool; either way
        // the probe must return a Boolean, not throw (catch-all path).
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        KubedroidPalette.isDynamicAvailable(ctx) // no exception == pass
    }

    @Test fun fixedLightPaletteSeedsKubernetesBlue() {
        var primary: androidx.compose.ui.graphics.Color? = null
        composeRule.setContent {
            KubedroidTheme(darkTheme = false, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        assertEquals(KubernetesBlue, primary)
    }

    @Test fun fixedDarkPaletteSeedsKubernetesBlue() {
        var primary: androidx.compose.ui.graphics.Color? = null
        composeRule.setContent {
            KubedroidTheme(darkTheme = true, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        assertEquals(KubernetesBlue, primary)
    }

    @Test fun expressiveShapeTokens_areGenerous() {
        // Q4A: cards/bottom sheets get the extraLarge expressive radius; the
        // tokens are asserted directly (Compose tests see the applied shape
        // only through components, which stage 4 exercises separately).
        assertEquals(RoundedCornerShape(28.dp), KubedroidShapes.extraLarge)
        assertEquals(RoundedCornerShape(24.dp), KubedroidShapes.large)
    }
}
