package com.neozeon.kubedroid.data.kubernetes

/**
 * Spec 06 "EditSanitizer": the single choke point between a user's edited
 * document and the wire. Server-managed fields the user cannot own are
 * stripped (so the common edit path doesn't 422), the loaded
 * `resourceVersion` is re-attached for the PUT precondition, and identity
 * fields are guarded so an edit can never be aimed at a different object.
 *
 * Pure Kotlin over unstructured maps — same helper style as ResourceCatalog.
 */
object EditSanitizer {

    /** Server-managed metadata, dropped for display AND before PUT. */
    private val METADATA_DROP = listOf(
        "creationTimestamp", "uid", "managedFields", "resourceVersion",
    )

    /** Document as shown in the YAML editor: no status, no server-owned metadata. */
    fun forDisplay(obj: Map<String, Any?>): Map<String, Any?> {
        val copy = deepCopyMap(obj)
        copy.remove("status")
        copy["metadata"]?.let { v -> asMutableMap(v)?.let { meta -> METADATA_DROP.forEach { meta.remove(it) } } }
        return copy
    }

    /**
     * Identity guard (spec 06 "EditSanitizer"): kind, apiVersion,
     * metadata.name and metadata.namespace must match the loaded object.
     * Returns the mismatching field label, or null when the edit is aimed
     * at the same object.
     */
    fun identityMismatch(edited: Map<String, Any?>, loaded: Map<String, Any?>): String? {
        for (key in listOf("kind", "apiVersion")) {
            if (edited[key] != loaded[key]) return key
        }
        val em = edited["metadata"] as? Map<*, *>
        val lm = loaded["metadata"] as? Map<*, *>
        for (key in listOf("name", "namespace")) {
            if (em?.get(key) != lm?.get(key)) return "metadata.$key"
        }
        return null
    }

    /**
     * Final PUT body: strip server-managed fields the user may have pasted
     * back (status included), then re-attach the resourceVersion READ AT
     * LOAD TIME — the optimistic-concurrency precondition, never the
     * user-edited value.
     */
    fun prepareForReplace(edited: Map<String, Any?>, loaded: Map<String, Any?>): Map<String, Any?> {
        val copy = deepCopyMap(edited)
        copy.remove("status")
        val meta = copy["metadata"]?.let { asMutableMap(it) }
            ?: LinkedHashMap<String, Any?>().also { copy["metadata"] = it }
        METADATA_DROP.forEach { meta.remove(it) }
        val loadedVersion = (loaded["metadata"] as? Map<*, *>)?.get("resourceVersion")
        if (loadedVersion != null) meta["resourceVersion"] = loadedVersion
        return copy
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMutableMap(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

    private fun deepCopyMap(map: Map<*, *>): MutableMap<String, Any?> =
        LinkedHashMap<String, Any?>().also { out ->
            map.forEach { (k, v) -> out[k as String] = deepCopy(v) }
        }

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> deepCopyMap(value)
        is List<*> -> value.map { deepCopy(it) }
        else -> value
    }
}
