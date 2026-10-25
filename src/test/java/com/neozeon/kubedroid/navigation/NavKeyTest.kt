package com.neozeon.kubedroid.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertSame
import org.junit.Test

class NavKeyTest {

    @Test
    fun homeKeySurvivesSerializationRoundTrip() {
        val json = Json.encodeToString(HomeKey.serializer(), HomeKey)
        val restored = Json.decodeFromString(HomeKey.serializer(), json)
        // NavKey is an object: round trip must return the identical instance
        // so back-stack equality after restore keeps working.
        assertSame(HomeKey, restored)
    }
}
