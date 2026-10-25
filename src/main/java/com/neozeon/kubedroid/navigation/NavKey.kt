package com.neozeon.kubedroid.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// All navigation keys live here and must be @Serializable so Nav3's
// saveable-state decorator can persist them across process death.

@Serializable
object HomeKey : NavKey
