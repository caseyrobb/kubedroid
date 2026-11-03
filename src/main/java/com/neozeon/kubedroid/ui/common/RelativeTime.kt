package com.neozeon.kubedroid.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Spec 12 goal 8: "5h ago" / "5mo ago" relative timestamps with an ISO
 * fallback beyond a year. Pure function of (timestamp, now) so tests pin a
 * fake clock. Future timestamps (clock skew) render "just now".
 */
object RelativeTime {
    private val ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Loose RFC3339 parse for server log timestamps: tolerates fractional
     * seconds ("...:33.123456789Z") by truncating to whole seconds; null on
     * anything unparseable (renderers then show the raw string).
     */
    fun parse(instantText: String?): Instant? {
        val s = instantText?.trimOrNull() ?: return null
        val normalized = if (REGEX_FRACTION.matches(s)) s.substringBefore('.') + "Z" else s
        return runCatching { Instant.parse(normalized) }.getOrNull()
    }

    private fun String.trimOrNull(): String? = trim().takeIf { it.isNotEmpty() }

    private val REGEX_FRACTION = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z$")

    fun ago(timestamp: Instant?, now: Instant = Instant.now()): String {
        if (timestamp == null) return "—"
        val delta = Duration.between(timestamp, now)
        if (delta.isNegative) return "just now"
        val s = delta.seconds
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            s < 30 * 86_400 -> "${s / 86_400}d ago"
            s < 365 * 86_400 -> "${s / (30L * 86_400)}mo ago"
            else -> ISO_UTC.format(timestamp)
        }
    }
}
