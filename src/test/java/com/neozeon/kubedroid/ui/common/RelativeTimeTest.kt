package com.neozeon.kubedroid.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Spec 12 stage 1: relative-time buckets against a fixed clock. */
class RelativeTimeTest {
    private val now: Instant = Instant.parse("2026-10-29T12:00:00Z")

    private fun ago(offsetSeconds: Long) =
        RelativeTime.ago(now.minusSeconds(offsetSeconds), now)

    @Test fun buckets() {
        assertEquals("0s ago", ago(0))
        assertEquals("59s ago", ago(59))
        assertEquals("1m ago", ago(60))
        assertEquals("59m ago", ago(3_599))
        assertEquals("1h ago", ago(3_600))
        assertEquals("23h ago", ago(86_399))
        assertEquals("1d ago", ago(86_400))
        assertEquals("29d ago", ago(30 * 86_400L - 1))
        assertEquals("1mo ago", ago(30 * 86_400L))
        assertEquals("11mo ago", ago(11 * 30 * 86_400L))
        assertEquals("12mo ago", ago(365 * 86_400L - 1))
    }

    @Test fun beyondYearFallsBackToIso() {
        assertEquals(
            "2025-10-29T12:00:00Z",
            RelativeTime.ago(Instant.parse("2025-10-29T12:00:00Z"), now),
        )
    }

    @Test fun futureIsJustNow() {
        assertEquals("just now", RelativeTime.ago(now.plusSeconds(5), now))
    }

    @Test fun nullTimestampIsEmDash() {
        assertEquals("—", RelativeTime.ago(null, now))
    }

    @Test fun parseAcceptsWholeSecondRfc3339() {
        assertEquals(
            Instant.parse("2026-10-25T12:00:00Z"),
            RelativeTime.parse("2026-10-25T12:00:00Z"),
        )
    }

    @Test fun parseTruncatesFractionalSeconds() {
        assertEquals(
            Instant.parse("2026-10-25T12:00:00Z"),
            RelativeTime.parse("2026-10-25T12:00:00.123456789Z"),
        )
    }

    @Test fun parseRejectsGarbageAndBlank() {
        assertNull(RelativeTime.parse("not-a-time"))
        assertNull(RelativeTime.parse("   "))
        assertNull(RelativeTime.parse(null))
    }
}
