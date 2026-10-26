package com.neozeon.kubedroid.util

import com.neozeon.kubedroid.data.model.RedactedValue
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Spec 04 "Resource detail": renders a live unstructured object as YAML for
 * the read-only YAML tab. Normalises Gson's Double materialisation back to
 * integers where exact (generation: 3, not 3.0) and stringifies
 * [RedactedValue] placeholders; block style, never wraps long scalars.
 */
object YamlRenderer {

    fun render(obj: Map<String, Any?>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            width = Int.MAX_VALUE
        }
        return Yaml(options).dump(normalize(obj))
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v) }
        is List<*> -> value.map { normalize(it) }
        is RedactedValue -> value.toString()
        is Double -> if (value.isFinite() && value == value.toLong().toDouble()) value.toLong() else value
        else -> value
    }
}
