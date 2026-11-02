package com.neozeon.kubedroid.data.model

import com.neozeon.kubedroid.ui.status.PodStatus
import com.neozeon.kubedroid.ui.status.StatusToken
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
    /**
     * Spec 12: semantic status token resolved once at extraction from the
     * raw object (single source of truth for row pill + health bar).
     * Unknown for kinds without a resolver path.
     */
    val statusToken: StatusToken = PodStatus.UNKNOWN,
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

    data class Network(val detail: String? = null) :
        ResourceError(
            "Could not reach the cluster. Check connectivity and retry." +
                (detail?.let { " ($it)" } ?: ""),
        )
    data class Server(val code: Int) : ResourceError("The cluster returned an error (HTTP $code).")
    data class Malformed(val detail: String) : ResourceError("The cluster response was not valid JSON: $detail")

    /**
     * Spec 05: pods/log is a separate RBAC resource/verb from pods — often
     * omitted while pod reads are granted, so the message must say so.
     */
    data class ForbiddenLogs(val namespace: String?) :
        ResourceError(
            "You do not have permission to read pod logs in " +
                (namespace?.let { "namespace $it" } ?: "all namespaces") +
                ". The rbac.authorization.k8s.io resource \\" +
                "\"pods/log\\\" (verb get) is granted separately from reading pods.",
        )

    /** Spec 05: local container validation (friendlier than the server's 400). */
    data class ContainerNotFound(val requested: String, val known: List<String>) :
        ResourceError(
            "Container '$requested' was not found in this pod. " +
                "This pod has: ${if (known.isEmpty()) "no containers" else known.joinToString(", ")}.",
        )

    /**
     * Spec 06: optimistic concurrency — the resourceVersion precondition in
     * our PUT no longer matches the stored object. No merge UI: reload.
     */
    data class Conflict(val kind: String, val name: String) :
        ResourceError(
            "$kind '$name' changed in the cluster since you opened it. " +
                "Reload it and re-apply your edit; nothing was overwritten.",
        )

    /** Spec 06: apiserver rejected the document (422); message surfaced verbatim. */
    data class Invalid(val detail: String) :
        ResourceError("The cluster rejected the change: $detail")

    /**
     * Spec 06: 403 on a WRITE verb. Distinct from [Forbidden] (list reads) —
     * the copy names verb and resource so RBAC can be fixed precisely.
     */
    data class ForbiddenVerb(val kind: String, val verb: String, val namespace: String?) :
        ResourceError(
            "You do not have permission to $verb $kind in " +
                (namespace?.let { "namespace $it" } ?: "the cluster") +
                ". The RBAC rule granting '$verb' on '$kind' is separate from read access.",
        )

    fun userMessage(): String = reason
}

/**
 * Spec 06 delete outcome. [existed] == false means the object was already
 * gone (404) — treated as success by the UI with an informational message.
 */
data class DeleteResult(val existed: Boolean)
