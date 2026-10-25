package com.neozeon.kubedroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.os.Build
import androidx.compose.material3.lightColorScheme

private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = OnSeed,
    surface = SeedSurface,
    onSurface = SeedOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = Seed,
    onPrimary = OnSeed,
    surface = SeedSurfaceDark,
    onSurface = SeedOnSurfaceDark,
)

@Composable
fun KubedroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KubedroidTypography,
        content = content,
    )
}
