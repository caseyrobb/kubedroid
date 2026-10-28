package com.neozeon.kubedroid.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 03 stage 11: the Reachable chip must carry a version TAG, not the
 * raw /version body.
 */
class VersionTagTest {
    @Test fun gitVersionPreferred() {
        assertEquals(
            "v1.36.4",
            versionTag("""{"major":"1","minor":"36","gitVersion":"v1.36.4"}"""),
        )
    }

    @Test fun nonJsonBodyFallsBackToRawPrefix() {
        assertEquals("plain text version", versionTag("  plain text version  "))
    }

    @Test fun jsonWithoutGitVersionFallsBackToPrefix() {
        assertEquals("""{"major":"1"}""", versionTag("""{"major":"1"}"""))
    }

    @Test fun nonObjectJsonFallsBackToPrefix() {
        assertEquals("[1,2]", versionTag("[1,2]"))
    }

    @Test fun blankBodyYieldsOk() {
        assertEquals("ok", versionTag("   "))
    }

    @Test fun longNonJsonBodyCappedAtForty() {
        val tag = versionTag("x".repeat(100))
        assertEquals(40, tag.length)
    }

    @Test fun blankGitVersionFallsBackToPrefix() {
        assertEquals(
            """{"gitVersion":""}""",
            versionTag("""{"gitVersion":""}"""),
        )
    }
}
