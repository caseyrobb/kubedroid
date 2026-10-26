package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.data.model.RedactedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 04 stage 4: Summary tab derivation from unstructured objects. */
class SummaryRowsTest {

    @Test fun headerFactsAndLabels() {
        val obj = mapOf(
            "metadata" to mapOf(
                "name" to "web-1",
                "namespace" to "web",
                "creationTimestamp" to "2026-01-01T00:00:00Z",
                "labels" to mapOf("app" to "frontend", "tier" to "web"),
            ),
        )
        val rows = SummaryRows.build(obj).toMap()
        assertEquals("web-1", rows["name"])
        assertEquals("web", rows["namespace"])
        assertEquals("2026-01-01T00:00:00Z", rows["created"])
        assertTrue(rows["labels"]!!.contains("app=frontend"))
        assertTrue(rows["labels"]!!.contains("tier=web"))
    }

    @Test fun statusAndSpecFlattened() {
        val obj = mapOf(
            "metadata" to mapOf("name" to "n"),
            "spec" to mapOf(
                "replicas" to 3.0,
                "template" to mapOf("spec" to mapOf("containers" to listOf(mapOf("name" to "app", "image" to "nginx")))),
            ),
            "status" to mapOf("phase" to "Running", "podIP" to "10.0.0.1"),
        )
        val rows = SummaryRows.build(obj)
        assertTrue(rows.any { it.first == "spec.replicas" && it.second == "3.0" })
        assertTrue(rows.any { it.first == "status.phase" && it.second == "Running" })
        assertTrue(rows.any { it.first == "status.podIP" && it.second == "10.0.0.1" })
        assertTrue(rows.any { it.second == "app" }) // container name surfaced
    }

    @Test fun secretDataShowsPlaceholderForRedactedValues() {
        val obj = mapOf(
            "metadata" to mapOf("name" to "db"),
            "type" to "Opaque",
            "data" to mapOf("pw" to RedactedValue(7), "user" to "YWRtaW4="),
        )
        val rows = SummaryRows.build(obj).toMap()
        assertEquals("Opaque", rows["type"])
        assertEquals("•••• (7 bytes)", rows["data.pw"])
        assertEquals("YWRtaW4=", rows["data.user"])
    }
}
