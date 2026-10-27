package com.neozeon.kubedroid.data.kubernetes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * spec 03 stage 9: ACCESS_LOCAL_NETWORK gating. Below API 36 the whole
 * surface must be dormant (the permission does not exist there); at/above
 * 36 it follows the grant state.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkAccessTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun dormantBelowApi36() {
        assertFalse(LocalNetworkAccess.isEnforced())
        // Even with nothing granted, everything reads as granted.
        assertTrue(LocalNetworkAccess.isGranted(context))
        assertNull(LocalNetworkAccess.missingHint(context))
    }

    @Test
    @Config(sdk = [36])
    fun enforcedAndDeniedAtApi36() {
        assertTrue(LocalNetworkAccess.isEnforced())
        assertFalse(LocalNetworkAccess.isGranted(context))
        val hint = LocalNetworkAccess.missingHint(context)
        assertNotNull(hint)
        assertTrue(hint!!.contains("local network"))
    }

    @Test
    @Config(sdk = [36])
    fun enforcedAndGrantedAtApi36() {
        val app = context.applicationContext as android.app.Application
        shadowOf(app).grantPermissions(LocalNetworkAccess.PERMISSION)
        assertTrue(LocalNetworkAccess.isGranted(context))
        assertNull(LocalNetworkAccess.missingHint(context))
    }
}
