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

    @Test fun logKeySurvivesSerializationRoundTrip() {
        // spec 05: LogKey round-trips both the auto-select (null container)
        // and explicit-container forms across process death.
        val id = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        for (key in listOf(
            LogKey(id, "web", "web-1", null),
            LogKey(id, "web", "web-1", "sidecar"),
        )) {
            val json = Json.encodeToString(LogKey.serializer(), key)
            assertEquals(key, Json.decodeFromString(LogKey.serializer(), json))
        }
    }
}
