package com.neozeon.kubedroid.ui.theme

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = KubernetesBlue,
    onPrimary = OnKubernetesBlue,
    primaryContainer = KubernetesBlueContainer,
    onPrimaryContainer = OnKubernetesBlueContainer,
    surface = SurfaceLight,
    background = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
)

private val DarkColors = darkColorScheme(
    primary = KubernetesBlue,
    onPrimary = OnKubernetesBlue,
    primaryContainer = KubernetesBlueDark,
    onPrimaryContainer = KubernetesBlueContainer,
    surface = SurfaceDark,
    background = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
)

// spec 08 (Q4A): expressive shape tokens — generous corner radii on cards
// and sheets; components themselves are unchanged.
val KubedroidShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * spec 08 Q5A: dynamic-color availability must be probed, not inferred from
 * the SDK level — GrapheneOS (and similar de-Googled ROMs) run Android 12+
 * while shipping no Material You; there dynamicLightColorScheme() returns
 * AOSP's generic palette instead of a coherent identity. The AOSP framework
 * bool `config_defaultDynamicColors` is the authoritative switch.
 */
object KubedroidPalette {

    fun isDynamicAvailable(context: Context): Boolean =
        dynamicSupported(Build.VERSION.SDK_INT, frameworkDynamicDefault(context))

    /** True from Android 12 (S) when the framework enables dynamic color. */
    internal fun dynamicSupported(sdkInt: Int, configDefaultDynamicColors: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.S && configDefaultDynamicColors

    private fun frameworkDynamicDefault(context: Context): Boolean = try {
        val id = Resources.getSystem()
            .getIdentifier("config_defaultDynamicColors", "bool", "android")
        id != 0 && context.resources.getBoolean(id)
    } catch (_: Throwable) {
        false
    }
}

@Composable
fun KubedroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // the inline SDK gate is redundant with isDynamicAvailable() but
        // lint's NewApi proof needs to see it at the @RequiresApi call site.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            KubedroidPalette.isDynamicAvailable(context) ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KubedroidTypography,
        shapes = KubedroidShapes,
        content = content,
    )
}
