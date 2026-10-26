package com.neozeon.kubedroid.data.kubernetes

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 07 "CatalogResolver": one entry-resolution point for every screen
 * and repository call — static [ResourceCatalog] first (a bad discovery
 * result can never shadow a built-in type), then the per-connection
 * discovered-CRD cache.
 *
 * Q1 human decision 2026-10-26: cache is in-memory, populated on first
 * discovery fetch, manual Refresh re-fetches; no TTL, no persistence.
 */
@Singleton
class CatalogResolver @Inject constructor() {

    private class Discovered(val definitions: List<CrdDefinition>) {
        /** (apiVersion, resource) -> entry for the definition's default version. */
        val entries: Map<Pair<String, String>, CatalogEntry> =
            definitions.associate { crd ->
                val version = crd.defaultVersion
                ("${crd.group}/$version" to crd.plural) to CrdDiscovery.entry(crd, version)
            }
    }

    private val cache = ConcurrentHashMap<UUID, Discovered>()

    /** Static table wins; discovered entries fill the gaps. Any served
     *  version resolves (version chips request non-default ones). */
    fun resolve(connectionId: UUID, apiVersion: String, resource: String): CatalogEntry? =
        ResourceCatalog.find(apiVersion, resource)
            ?: cache[connectionId]?.entries?.get(apiVersion to resource)
            ?: definition(connectionId, apiVersion, resource)?.let {
                CrdDiscovery.entry(it, apiVersion.substringAfterLast('/'))
            }

    /** Definition backing a discovered entry (version chips, stage 3). */
    fun definition(connectionId: UUID, apiVersion: String, resource: String): CrdDefinition? {
        val group = apiVersion.substringBeforeLast('/', "")
        return cache[connectionId]?.definitions?.firstOrNull { it.group == group && it.plural == resource }
    }

    /** Entry for an explicit version (version-chip switch); null if unknown. */
    fun entryFor(connectionId: UUID, apiVersion: String, resource: String, version: String?): CatalogEntry? {
        ResourceCatalog.find(apiVersion, resource)?.let { return it }
        val crd = definition(connectionId, apiVersion, resource) ?: return null
        val v = version?.takeIf { it in crd.servedVersions } ?: crd.defaultVersion
        return CrdDiscovery.entry(crd, v)
    }

    /** Publish a fresh discovery result (called by CrdRepository after a fetch). */
    fun publish(connectionId: UUID, definitions: List<CrdDefinition>) {
        cache[connectionId] = Discovered(definitions)
    }

    fun cached(connectionId: UUID): List<CrdDefinition>? = cache[connectionId]?.definitions

    fun clear(connectionId: UUID) {
        cache.remove(connectionId)
    }
}
