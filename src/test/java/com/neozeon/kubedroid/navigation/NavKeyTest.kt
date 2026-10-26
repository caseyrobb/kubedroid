package com.neozeon.kubedroid.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class NavKeyTest {

    @Test fun homeKeySurvivesSerializationRoundTrip() {
        val json = Json.encodeToString(HomeKey.serializer(), HomeKey)
        val restored = Json.decodeFromString(HomeKey.serializer(), json)
        // NavKey is an object: round trip must return the identical instance
        // so back-stack equality after restore keeps working.
        assertSame(HomeKey, restored)
    }

    @Test fun browseKeySurvivesSerializationRoundTrip() {
        // spec 04: Nav3's saveable-state decorator persists keys across
        // process death — the UUID payload must round-trip exactly.
        val key = BrowseKey(UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"))
        val json = Json.encodeToString(BrowseKey.serializer(), key)
        assertEquals(key, Json.decodeFromString(BrowseKey.serializer(), json))
    }
}
