package com.neozeon.kubedroid.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.neozeon.kubedroid.data.kubernetes.ResourceCatalog
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.model.DeleteResult
import com.neozeon.kubedroid.data.model.RedactedValue
import com.neozeon.kubedroid.data.model.ResourceDetail
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourcePage
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.ResourceResult
import com.neozeon.kubedroid.data.model.ResourceSummary
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 04 "ResourceRepository": catalog lookup, gateway calls, one-shot
 * error mapping, and Secret redaction (applied here, not in the UI, so no
 * path can forget to hide values). Pure Kotlin — no Android imports.
 */
@Singleton
class ResourceRepository @Inject constructor(
    private val gateway: ResourceGateway,
    private val clusterRepository: ClusterRepository,
    /** Spec 07: static table first, discovered CRD entries second. */
    private val resolver: com.neozeon.kubedroid.data.kubernetes.CatalogResolver =
        com.neozeon.kubedroid.data.kubernetes.CatalogResolver(),
) {

    private fun contextFor(connectionId: UUID): String? =
        clusterRepository.connections.value.firstOrNull { it.id == connectionId }?.contextName

    private fun notFound(connectionId: UUID) =
        ResourceResult.Err(ResourceError.Malformed("connection $connectionId is no longer available"))

    suspend fun list(
        connectionId: UUID,
        apiVersion: String,
        resource: String,
        namespace: String?,
        continueToken: String? = null,
        labelSelector: String? = null,
    ): ResourceResult<ResourcePage> {
        val entry = resolver.resolve(connectionId, apiVersion, resource)
            ?: return ResourceResult.Err(ResourceError.Malformed("unknown resource type '$resource'"))
        val context = contextFor(connectionId) ?: return notFound(connectionId)
        return when (val r = gateway.list(
            connectionId, context, entry, namespace,
            continueToken = continueToken, labelSelector = labelSelector,
        )) {
            is ResourceGateway.Result.Ok -> ResourceResult.Ok(entry.toPage(r.json))
            is ResourceGateway.Result.Http -> ResourceResult.Err(mapHttp(r.code, entry.kind, namespace))
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    /** Detail fetch. Secret values are redacted unless [reveal] is true. */
    suspend fun detail(ref: ResourceRef, reveal: Boolean = false): ResourceResult<ResourceDetail> {
        val entry = resolver.resolve(ref.connectionId, ref.apiVersion, ref.resource)
            ?: return ResourceResult.Err(ResourceError.Malformed("unknown resource type '${ref.resource}'"))
        val context = contextFor(ref.connectionId) ?: return notFound(ref.connectionId)
        return when (val r = gateway.get(ref.connectionId, context, entry, ref.namespace, ref.name ?: "")) {
            is ResourceGateway.Result.Ok -> {
                val obj = if (entry.kind == "Secret" && !reveal) redactSecretValues(r.json) else r.json
                ResourceResult.Ok(ResourceDetail(ref, obj))
            }
            is ResourceGateway.Result.Http ->
                ResourceResult.Err(if (r.code == 404) ResourceError.NotFound(entry.kind, ref.name ?: "?") else mapHttp(r.code, entry.kind, ref.namespace))
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    /** Namespace names for the scope dropdown (cluster-scoped list). */
    suspend fun namespaces(connectionId: UUID): ResourceResult<List<String>> {
        val entry = ResourceCatalog.find("v1", "namespaces")!!
        val context = contextFor(connectionId) ?: return notFound(connectionId)
        return when (val r = gateway.list(connectionId, context, entry, null)) {
            is ResourceGateway.Result.Ok -> ResourceResult.Ok(
                r.json.items().mapNotNull { (it as? Map<*, *>)?.str("metadata", "name") },
            )
            is ResourceGateway.Result.Http -> ResourceResult.Err(mapHttp(r.code, "namespaces", null))
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    // ---- spec 06: mutations (never retried; see spec 06 "Endpoints") ----

    /**
     * Replace (PUT). [edited] is the user's document already prepared by the
     * ViewModel through [com.neozeon.kubedroid.data.kubernetes.EditSanitizer]
     * (identity-validated, status stripped, loaded resourceVersion attached).
     */
    suspend fun replace(ref: ResourceRef, edited: Map<String, Any?>): ResourceResult<Unit> {
        val entry = resolver.resolve(ref.connectionId, ref.apiVersion, ref.resource)
            ?: return ResourceResult.Err(ResourceError.Malformed("unknown resource type '${ref.resource}'"))
        val context = contextFor(ref.connectionId) ?: return notFound(ref.connectionId)
        return when (
            val r = gateway.replace(
                ref.connectionId, context, entry, ref.namespace, ref.name ?: "", gson.toJson(edited),
            )
        ) {
            is ResourceGateway.Result.Ok -> ResourceResult.Ok(Unit)
            is ResourceGateway.Result.Http -> ResourceResult.Err(
                mapMutation(r.code, entry.kind, "update", ref.namespace, ref.name, r.bodySnippet),
            )
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    /**
     * Delete. A 404 means the object was already gone — returned as
     * `DeleteResult(existed = false)` (success), per spec 06 "Delete flow".
     */
    suspend fun delete(ref: ResourceRef): ResourceResult<DeleteResult> {
        val entry = resolver.resolve(ref.connectionId, ref.apiVersion, ref.resource)
            ?: return ResourceResult.Err(ResourceError.Malformed("unknown resource type '${ref.resource}'"))
        val context = contextFor(ref.connectionId) ?: return notFound(ref.connectionId)
        return when (val r = gateway.delete(ref.connectionId, context, entry, ref.namespace, ref.name ?: "")) {
            is ResourceGateway.Result.Ok -> ResourceResult.Ok(DeleteResult(existed = true))
            is ResourceGateway.Result.Http ->
                if (r.code == 404) ResourceResult.Ok(DeleteResult(existed = false))
                else ResourceResult.Err(
                    mapMutation(r.code, entry.kind, "delete", ref.namespace, ref.name, r.bodySnippet),
                )
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    /** Mutation mapping point (spec 06): mirrors mapHttp + write-verb errors. */
    private fun mapMutation(
        code: Int,
        kind: String,
        verb: String,
        namespace: String?,
        name: String?,
        bodySnippet: String,
    ): ResourceError = when (code) {
        401 -> ResourceError.Unauthorized
        403 -> ResourceError.ForbiddenVerb(kind, verb, namespace)
        404 -> ResourceError.NotFound(kind, name ?: "?")
        409 -> ResourceError.Conflict(kind, name ?: "?")
        422 -> ResourceError.Invalid(
            extractStatusMessage(bodySnippet) ?: "the API server rejected the request (422).",
        )
        else -> ResourceError.Server(code)
    }

    /** apiserver Status JSON carries the actionable `message`; surface it verbatim. */
    private fun extractStatusMessage(bodySnippet: String): String? = try {
        (gson.fromJson(bodySnippet, MAP_TYPE) as? Map<*, *>)?.get("message") as? String
    } catch (e: Exception) {
        null
    }

    /** Single mapping point HTTP code -> user-actionable error (spec 04). */
    private fun mapHttp(code: Int, kind: String, namespace: String?): ResourceError = when (code) {
        401 -> ResourceError.Unauthorized
        403 -> ResourceError.Forbidden(kind, namespace)
        404 -> ResourceError.NotFound(kind, namespace ?: "<none>")
        else -> ResourceError.Server(code)
    }

    private fun com.neozeon.kubedroid.data.kubernetes.CatalogEntry.toPage(json: Map<String, Any?>): ResourcePage {
        val items = json.items().mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val obj = raw as? Map<String, Any?> ?: return@mapNotNull null
            val meta = obj["metadata"] as? Map<*, *>
            val status = statusExtractor(obj)
            ResourceSummary(
                apiVersion = apiVersion,
                kind = kind,
                name = meta.str("name") ?: "<unnamed>",
                namespace = meta.str("namespace"),
                labels = (meta?.get("labels") as? Map<*, *>)
                    ?.entries?.associate { (k, v) -> k.toString() to v.toString() }
                    ?: emptyMap(),
                primary = status.primary,
                secondary = status.secondary,
                createdAt = meta.str("creationTimestamp"),
                // spec 12: one semantic token per row, resolved from the raw
                // object while it is still in hand (data -> ui.status import
                // is a deliberate single-module decision, recorded in spec 12).
                statusToken = com.neozeon.kubedroid.ui.status.resolveStatus(obj, kind),
            )
        }
        val cont = (json["metadata"] as? Map<*, *>)?.get("continue") as? String
        return ResourcePage(items, cont?.takeIf { it.isNotEmpty() })
    }

    companion object {
        private val gson = Gson()
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        // ---- unstructured-map helpers (Gson materialises Map<String,Any?>) ----

        private fun Map<String, Any?>?.items(): List<Any?> =
            (this?.get("items") as? List<Any?>) ?: emptyList()

        private fun Map<*, *>?.str(vararg path: String): String? {
            var cur: Any? = this
            for (key in path) cur = (cur as? Map<*, *>)?.get(key) ?: return null
            return cur as? String
        }

        /**
         * Replace every `data`/`stringData` value with [RedactedValue]
         * (decoded length only — the decoded bytes never escape this function).
         */
        fun redactSecretValues(obj: Map<String, Any?>): Map<String, Any?> {
            fun redactBlock(block: Any?): Any? =
                (block as? Map<*, *>)?.entries?.associate { (k, v) ->
                    k.toString() to RedactedValue(decodedLength(v))
                }
            val out = LinkedHashMap(obj)
            redactBlock(obj["data"])?.let { out["data"] = it }
            redactBlock(obj["stringData"])?.let { out["stringData"] = it }
            return out
        }

        private fun decodedLength(value: Any?): Int = when (value) {
            is String -> runCatching { Base64.getDecoder().decode(value).size }
                .getOrElse { value.toByteArray().size } // stringData / non-base64: raw size
            null -> 0
            else -> value.toString().toByteArray().size
        }
    }
}
