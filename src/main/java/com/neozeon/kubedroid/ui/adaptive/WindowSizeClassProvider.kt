package com.neozeon.kubedroid.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * Single access point for the current window size class, so feature specs
 * never import adaptive machinery directly. Skeleton ships the helper
 * without wiring scene strategies (no list-detail destinations yet).
 * Uses the V2 adaptive info so Large/Extra-large width classes are reported.
 */
@Composable
fun currentKubedroidWindowSize(): WindowSizeClass =
    currentWindowAdaptiveInfoV2().windowSizeClass
