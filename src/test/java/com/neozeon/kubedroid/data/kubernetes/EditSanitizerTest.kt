package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.util.YamlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * Spec 06 stage 1 Testing: the wire-safety choke point. Display stripping,
 * PUT body preparation (loaded resourceVersion wins, pasted-back status
 * dies), the four identity guards, and YAML round-trip stability.
 */
class EditSanitizerTest {

    private fun loaded(): MutableMap<String, Any?> = mutableMapOf(
        "apiVersion" to "v1",
        "kind" to "Pod",
        "metadata" to mutableMapOf<String, Any?>(
            "name" to "web-1",
            "namespace" to "web",
            "resourceVersion" to "12345",
            "uid" to "abc-uid",
            "creationTimestamp" to "2026-01-01T00:00:00Z",
            "managedFields" to listOf(mapOf("manager" to "kubelet")),
            "labels" to mapOf("app" to "web"),
        ),
        "spec" to mutableMapOf<String, Any?>("containers" to listOf(mapOf("name" to "app", "image" to "nginx"))),
        "status" to mutableMapOf<String, Any?>("phase" to "Running"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.meta() = this["metadata"] as Map<String, Any?>

    @Test fun forDisplayStripsStatusAndServerOwnedMetadata() {
        val view = EditSanitizer.forDisplay(loaded())
        assertNull("status must never reach the editor", view["status"])
        val meta = view.meta()
        assertNull(meta["resourceVersion"])
        assertNull(meta["uid"])
        assertNull(meta["creationTimestamp"])
        assertNull(meta["managedFields"])
        assertEquals("web-1", meta["name"]) // user-owned fields survive
        assertEquals(mapOf("app" to "web"), meta["labels"])
    }

    @Test fun forDisplayDoesNotMutateSource() {
        val original = loaded()
        EditSanitizer.forDisplay(original)
        assertNotNull("source object must be untouched", original["status"])
    }

    @Test fun prepareForReplaceAttachesLoadedVersionAndDropsPastedStatus() {
        val edited = loaded().also {
            it["status"] = mutableMapOf("phase" to "Succeeded") // user pasted one back
            (it["metadata"] as MutableMap<String, Any?>)["resourceVersion"] = "99999" // user edited it
        }
        val body = EditSanitizer.prepareForReplace(edited, loaded())
        assertNull(body["status"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            "the LOADED resourceVersion is the precondition, not the user's value",
            "12345", (body["metadata"] as Map<String, Any?>)["resourceVersion"],
        )
    }

    @Test fun prepareForReplaceAddsMetadataWhenMissing() {
        val edited = mapOf("apiVersion" to "v1", "kind" to "Pod")
        val body = EditSanitizer.prepareForReplace(edited, loaded())
        @Suppress("UNCHECKED_CAST")
        assertEquals("12345", (body["metadata"] as Map<String, Any?>)["resourceVersion"])
    }

    @Test fun identityMismatchCatchesAllFourFields() {
        fun edit(mutate: (MutableMap<String, Any?>) -> Unit): MutableMap<String, Any?> =
            loaded().also(mutate)

        assertEquals("kind", EditSanitizer.identityMismatch(
            edit { it["kind"] = "Deployment" }, loaded()))
        assertEquals("apiVersion", EditSanitizer.identityMismatch(
            edit { it["apiVersion"] = "apps/v1" }, loaded()))
        assertEquals("metadata.name", EditSanitizer.identityMismatch(
            edit { (it["metadata"] as MutableMap<String, Any?>)["name"] = "web-2" }, loaded()))
        assertEquals("metadata.namespace", EditSanitizer.identityMismatch(
            edit { (it["metadata"] as MutableMap<String, Any?>)["namespace"] = "default" }, loaded()))
    }

    @Test fun identityAllowsSpecAndLabelEdits() {
        val edited = loaded().also {
            @Suppress("UNCHECKED_CAST")
            (it["spec"] as MutableMap<String, Any?>)["containers"] =
                listOf(mapOf("name" to "app", "image" to "nginx:1.27"))
        }
        assertNull(EditSanitizer.identityMismatch(edited, loaded()))
    }

    @Test fun yamlRoundTripSurvivesRenderAndParse() {
        val view = EditSanitizer.forDisplay(loaded())
        val reparsed = Yaml().load<Map<String, Any?>>(YamlRenderer.render(view))
        assertEquals(
            "render -> parse must be map-equal so the editor never shifts values",
            view, reparsed,
        )
    }

    private fun assertNotNull(msg: String, value: Any?) = assertTrue(msg, value != null)
}
