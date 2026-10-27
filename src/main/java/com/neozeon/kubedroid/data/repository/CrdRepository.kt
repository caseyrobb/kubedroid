package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.CatalogResolver
import com.neozeon.kubedroid.data.kubernetes.CrdDefinition
import com.neozeon.kubedroid.data.kubernetes.CrdDiscovery
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 07 "CrdRepository": CRD discovery fetch + error mapping, feeding the
 * [CatalogResolver] cache. Reads keep the spec 03 retry policy (gateway
 * GET retry); nothing here mutates.
 *
 * Q6 human decision 2026-10-26: CRDs are READ-ONLY for now — this
 * repository exposes no delete/replace surface for CRD objects themselves
 * (CR instances mutate through ResourceRepository via the spec 06 path).
 */
@Singleton
class CrdRepository @Inject constructor(
    private val gateway: ResourceGateway,
    private val clusterRepository: ClusterRepository,
    private val resolver: CatalogResolver,
) {

    /**
     * List discovered CRDs. Cached after the first successful fetch
     * (Q1 human decision 2026-10-26); [refresh] = true re-fetches and
     * re-publishes. Malformed CRDs are skipped inside [CrdDiscovery].
     */
    suspend fun list(
        connectionId: UUID,
        refresh: Boolean = false,
        continueToken: String? = null,
    ): ResourceResult<List<CrdDefinition>> {
        if (!refresh && continueToken == null) resolver.cached(connectionId)?.let { return ResourceResult.Ok(it) }
        val context = clusterRepository.connections.value
            .firstOrNull { it.id == connectionId }?.contextName
            ?: return ResourceResult.Err(ResourceError.Malformed("connection $connectionId is no longer available"))

        return when (
            val r = gateway.list(connectionId, context, CrdDiscovery.crdListEntry, null, continueToken = continueToken)
        ) {
            is ResourceGateway.Result.Ok -> {
                val defs = CrdDiscovery.parseList(r.json)
                if (continueToken == null) {
                    resolver.publish(connectionId, defs)
                    ResourceResult.Ok(defs)
                } else {
                    // paged tail: merge into the published set
                    val merged = (resolver.cached(connectionId).orEmpty() + defs).distinctBy { it.name }
                    resolver.publish(connectionId, merged)
                    ResourceResult.Ok(merged)
                }
            }
            is ResourceGateway.Result.Http -> ResourceResult.Err(mapHttp(r.code))
            is ResourceGateway.Result.Network -> ResourceResult.Err(ResourceError.Network(r.detail))
            is ResourceGateway.Result.Malformed -> ResourceResult.Err(ResourceError.Malformed(r.detail))
        }
    }

    /** 403 must name `customresourcedefinitions` (spec 07 "RBAC parity"). */
    private fun mapHttp(code: Int): ResourceError = when (code) {
        401 -> ResourceError.Unauthorized
        403 -> ResourceError.Forbidden("customresourcedefinitions", null)
        404 -> ResourceError.NotFound("CustomResourceDefinition", "<none>")
        else -> ResourceError.Server(code)
    }
}
