package com.neozeon.kubedroid.util

import com.neozeon.kubedroid.data.model.RedactedValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 04 stage 3: YAML rendering of unstructured API objects. */
class YamlRendererTest {

    @Test fun integerNumbersAreNotDoubles() {
        val yaml = YamlRenderer.render(mapOf("metadata" to mapOf("generation" to 3.0), "replicas" to 2.0))
        assertTrue(yaml, yaml.contains("generation: 3"))
        assertTrue(yaml, yaml.contains("replicas: 2"))
        assertFalse(yaml, yaml.contains("3.0"))
    }

    @Test fun redactedValuesRenderAsPlaceholderText() {
        val yaml = YamlRenderer.render(mapOf("data" to mapOf("password" to RedactedValue(7))))
        assertTrue(yaml, yaml.contains("password: •••• (7 bytes)"))
    }

    @Test fun nestedListsAndStrings() {
        val yaml = YamlRenderer.render(
            mapOf("spec" to mapOf("containers" to listOf(mapOf("name" to "app", "ports" to listOf(mapOf("containerPort" to 8080.0)))))),
        )
        assertTrue(yaml, yaml.contains("- name: app"))
        assertTrue(yaml, yaml.contains("containerPort: 8080"))
    }

    @Test fun longScalarsDoNotWrap() {
        val longValue = "A".repeat(500) + "="
        val yaml = YamlRenderer.render(mapOf("data" to mapOf("ca" to longValue)))
        assertTrue(yaml, yaml.contains(longValue)) // base64 must stay on one line
    }
}
