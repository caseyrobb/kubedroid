package com.neozeon.kubedroid.data.model

import java.util.UUID

/**
 * Spec 04 domain models. Objects stay unstructured maps straight from the
 * API server (one generic GET path for every type); typed access happens in
 * the catalog's status extractors and the Summary tab only.
 */

/** Identifies a list scope (name == null) or a single object. */
data class ResourceRef(
    val connectionId: UUID,
    val apiVersion: String,
    val resource: String,
    val namespace: String? = null,
    val name: String? = null,
)

/** One row in a resource list. primary/secondary come from the catalog extractor. */
data class ResourceSummary(
    val apiVersion: String,
    val kind: String,
    val name: String,
    val namespace: String?,
    val labels: Map<String, String>,
    val primary: String,
    val secondary: String?,
    val createdAt: String?,
)

/** One page of list results; [continueToken] == null means the end. */
data class ResourcePage(val items: List<ResourceSummary>, val continueToken: String?)

/** Generic repository outcome: value or a mapped, user-actionable error. */
sealed interface ResourceResult<out T> {
    data class Ok<out T>(val value: T) : ResourceResult<T>
    data class Err(val error: ResourceError) : ResourceResult<Nothing>
}

/** Full object for the detail screen (never persisted; memory only). */
data class ResourceDetail(val ref: ResourceRef, val obj: Map<String, Any?>)

/**
 * Placeholder for a redacted Secret value (spec 04 "Secure handling"):
 * knows only the DECODED byte length — never the bytes or the base64 text.
 * Redaction happens in the repository, so no UI path can forget to hide it.
 */
data class RedactedValue(val decodedByteLength: Int) {
    override fun toString(): String = "•••• ($decodedByteLength bytes)"
}

/** User-actionable errors, mapped once in ResourceRepository (spec 04). */
sealed class ResourceError(open val reason: String) {
    data object Unauthorized : ResourceError("The cluster rejected your credentials.")
    data class Forbidden(val kind: String, val namespace: String?) :
        ResourceError(
            "You do not have permission to list $kind in " +
                (namespace?.let { "namespace $it" } ?: "all namespaces") + ".",
        )

    data class NotFound(val kind: String, val name: String) :
        ResourceError("$kind '$name' was not found (it may have been deleted).")

    data object Network : ResourceError("Could not reach the cluster. Check connectivity and retry.")
    data class Server(val code: Int) : ResourceError("The cluster returned an error (HTTP $code).")
    data class Malformed(val detail: String) : ResourceError("The cluster response was not valid JSON: $detail")

    fun userMessage(): String = reason
}
