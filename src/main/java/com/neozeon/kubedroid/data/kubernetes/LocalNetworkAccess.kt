package com.neozeon.kubedroid.data.kubernetes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * spec 03 stage 9: Android 16+ (API 36) blocks apps that *target* SDK
 * 36+ from opening TCP connections to LAN/private addresses unless
 * ACCESS_LOCAL_NETWORK is declared and granted — ungranted SYNs are
 * dropped in-kernel, so every cluster connect presents as a silent
 * timeout. Below API 36 the permission does not exist and everything is
 * treated as granted so the request path and hints stay dormant.
 */
object LocalNetworkAccess {
    // Not in the public Manifest constants of every supported compileSdk;
    // reference the platform name literally.
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** True when the platform enforces local-network access at all. */
    fun isEnforced(): Boolean = Build.VERSION.SDK_INT >= 36

    fun isGranted(context: Context): Boolean =
        !isEnforced() || ContextCompat.checkSelfPermission(
            context,
            PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Extra sentence appended to connectivity failures when the
     * missing permission is the (likely) cause. */
    fun missingHint(context: Context): String? =
        if (!isGranted(context)) {
            " Android 16+ also requires local network access for private" +
                " IP clusters — grant it when prompted or in app settings."
        } else {
            null
        }
}
