package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.R
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 08 "catalogIcon": kind -> official Kubernetes icon. Static catalog
 * rows and every CRD-derived entry resolve through this single helper so
 * unknown/custom kinds always land on the official crd.svg instead of a
 * blank. Node has no upstream icon in the resource set; it uses a
 * Material Apache-2.0 server-rack glyph (ic_k8s_node). [apiVersion] is
 * carried for future group-specific overrides; only [kind] keys today.
 */
@androidx.annotation.DrawableRes
fun catalogIcon(apiVersion: String, kind: String): Int = when (kind) {
    "Pod" -> R.drawable.ic_k8s_pod
    "Deployment" -> R.drawable.ic_k8s_deploy
    "ReplicaSet" -> R.drawable.ic_k8s_rs
    "DaemonSet" -> R.drawable.ic_k8s_ds
    "StatefulSet" -> R.drawable.ic_k8s_sts
    "Job" -> R.drawable.ic_k8s_job
    "CronJob" -> R.drawable.ic_k8s_cronjob
    "Service" -> R.drawable.ic_k8s_svc
    "Ingress" -> R.drawable.ic_k8s_ing
    "ConfigMap" -> R.drawable.ic_k8s_cm
    "Secret" -> R.drawable.ic_k8s_secret
    "ServiceAccount" -> R.drawable.ic_k8s_sa
    "Namespace" -> R.drawable.ic_k8s_ns
    "Node" -> R.drawable.ic_k8s_node
    "PersistentVolume" -> R.drawable.ic_k8s_pv
    "PersistentVolumeClaim" -> R.drawable.ic_k8s_pvc
    "StorageClass" -> R.drawable.ic_k8s_sc
    "LimitRange" -> R.drawable.ic_k8s_limits
    "ResourceQuota" -> R.drawable.ic_k8s_quota
    "NetworkPolicy" -> R.drawable.ic_k8s_netpol
    "HorizontalPodAutoscaler" -> R.drawable.ic_k8s_hpa
    "Endpoints" -> R.drawable.ic_k8s_ep
    "Role" -> R.drawable.ic_k8s_role
    "ClusterRole" -> R.drawable.ic_k8s_c_role
    "RoleBinding" -> R.drawable.ic_k8s_rb
    "ClusterRoleBinding" -> R.drawable.ic_k8s_crb
    "Group" -> R.drawable.ic_k8s_group
    "User" -> R.drawable.ic_k8s_user
    else -> R.drawable.ic_k8s_crd
}

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
