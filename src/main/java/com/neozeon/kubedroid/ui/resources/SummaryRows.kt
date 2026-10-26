package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.data.model.RedactedValue

/**
 * Spec 04 "Summary tab": the glanceable key/value rows for one object,
 * derived purely from the unstructured map so it is unit-testable without
 * Compose. Values already redacted by the repository arrive as
 * [RedactedValue] and stringify to the placeholder.
 */
object SummaryRows {

    /** Ordered rows for the Summary tab: header facts, then spec/status/data sections. */
    fun build(obj: Map<String, Any?>): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val meta = obj["metadata"] as? Map<*, *>
        val labels = meta?.get("labels") as? Map<*, *>

        meta?.get("name")?.let { rows += "name" to it.toString() }
        meta?.get("namespace")?.let { rows += "namespace" to it.toString() }
        meta?.get("creationTimestamp")?.let { rows += "created" to it.toString() }
        // Spec 07 (Q4): the generic fallback set for discovered CRs also
        // serves built-in types — apiVersion/kind header facts, and the
        // condition types currently True.
        (obj["apiVersion"] as? String)?.let { rows += "apiVersion" to it }
        (obj["kind"] as? String)?.let { rows += "kind" to it }
        (obj["type"] as? String)?.let { rows += "type" to it } // Secrets
        val conditions = (obj["status"] as? Map<*, *>)?.get("conditions") as? List<*>
        conditions?.mapNotNull { raw ->
            (raw as? Map<*, *>)?.takeIf { it["status"] == "True" }?.get("type")?.toString()
        }?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { rows += "conditions" to it.joinToString(", ") }
        if (!labels.isNullOrEmpty()) {
            rows += "labels" to labels.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }

        section(obj, "spec").forEach { rows += it }
        section(obj, "status").forEach { rows += it }
        section(obj, "data").forEach { rows += it }
        return rows
    }

    /**
     * Flatten one top-level section into `path: value` rows. Lists of maps
     * collapse to their `name`/`image` facts so Pods don't dump every env
     * var; scalars pass through; nulls are skipped.
     */
    private fun section(obj: Map<String, Any?>, key: String): List<Pair<String, String>> {
        val root = obj[key] as? Map<*, *> ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        walk(key, root, out)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun walk(prefix: String, node: Map<*, *>, out: MutableList<Pair<String, String>>) {
        for ((k, v) in node) {
            val path = if (prefix.isEmpty()) k.toString() else "$prefix.${k}"
            when (v) {
                null -> Unit
                is Map<*, *> -> walk(path, v, out)
                is List<*> -> {
                    v.forEachIndexed { i, item ->
                        when (item) {
                            // Containers, ports, conditions: flatten each entry
                            // (name/image/phase/... become their own rows).
                            is Map<*, *> -> walk("$path[$i]", item, out)
                            is RedactedValue -> out += "$path[$i]" to item.toString()
                            null -> Unit
                            else -> out += "$path[$i]" to item.toString()
                        }
                    }
                }
                else -> out += path to v.toString()
            }
        }
    }
}
