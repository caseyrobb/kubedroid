package com.neozeon.kubedroid.data.kubernetes

/**
 * Spec 04 "Resource catalog": the frozen MVP table of browsable types.
 * Pure Kotlin — paths are unit-tested row by row, extractors against real
 * API-server JSON shapes. Adding a type = one row + one extractor + one test;
 * CR instances arrive later via dynamic discovery, not by growing this table.
 */

enum class CatalogGroup(val display: String) {
    WORKLOADS("Workloads"),
    NETWORK("Network"),
    CONFIG("Config"),
    STORAGE("Storage"),
    CLUSTER("Cluster"),
}

/** List-row status produced by a catalog extractor. */
data class StatusLine(val primary: String, val secondary: String?)

data class CatalogEntry(
    val displayName: String,
    val kind: String,
    val group: CatalogGroup,
    val apiVersion: String,
    val resource: String,
    val namespaced: Boolean,
    val statusExtractor: (Map<String, Any?>) -> StatusLine,
)

object ResourceCatalog {

    /** e.g. core "v1" -> "api/v1"; grouped "apps/v1" -> "apis/apps/v1". */
    private fun apiPrefix(apiVersion: String): String =
        if (apiVersion.contains('/')) "apis/$apiVersion" else "api/$apiVersion"

    /** List URL path; namespace == null means all-namespaces scope. */
    fun listPath(entry: CatalogEntry, namespace: String?): String {
        require(namespace == null || entry.namespaced) {
            "${entry.kind} is cluster-scoped; it has no namespace scope"
        }
        val ns = if (entry.namespaced && namespace != null) "/namespaces/$namespace" else ""
        return "/${apiPrefix(entry.apiVersion)}$ns/${entry.resource}"
    }

    /** Single-object URL path. */
    fun getPath(entry: CatalogEntry, namespace: String?, name: String): String =
        listPath(entry, namespace) + "/" + name

    /**
     * Single-object path for mutations (spec 06). Like [logPath], namespace
     * and name are percent-encoded — mutation URLs are built from user-edited
     * context, so segments must not be able to escape their slot.
     */
    fun itemPath(entry: CatalogEntry, namespace: String?, name: String): String {
        require(namespace == null || entry.namespaced) {
            "${entry.kind} is cluster-scoped; it has no namespace scope"
        }
        val ns = if (entry.namespaced && namespace != null) "/namespaces/${encodeSegment(namespace)}" else ""
        return "/${apiPrefix(entry.apiVersion)}$ns/${entry.resource}/${encodeSegment(name)}"
    }

    /**
     * Pod log subresource path (spec 05 "Endpoint"). Always namespaced;
     * namespace and name are percent-encoded so pathological identifiers
     * cannot escape their path segment.
     */
    fun logPath(entry: CatalogEntry, namespace: String, name: String): String =
        "/${apiPrefix(entry.apiVersion)}/namespaces/${encodeSegment(namespace)}" +
            "/${entry.resource}/${encodeSegment(name)}/log"

    /** Unreserved characters kept readable; everything else UTF-8 percent-encoded. */
    internal fun encodeSegment(value: String): String =
        buildString {
            for (ch in value) {
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '.' || ch == '_' || ch == '~'
                ) append(ch) else for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%'); append(HEX[(b.toInt() shr 4) and 0xF]); append(HEX[b.toInt() and 0xF])
                }
            }
        }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun find(apiVersion: String, resource: String): CatalogEntry? =
        entries.firstOrNull { it.apiVersion == apiVersion && it.resource == resource }

    /**
     * Pod container names from the unstructured object (spec 05 log container
     * selector): (regular, init). Pure map access, same helpers as the
     * status extractors.
     */
    fun containerNames(obj: Map<String, Any?>): Pair<List<String>, List<String>> {
        fun names(key: String): List<String> {
            val spec = obj.asMap()?.get("spec").asMap() ?: return emptyList()
            val list = spec[key].asList() ?: return emptyList()
            return list.mapNotNull { it.asMap()?.get("name") as? String }
        }
        return names("containers") to names("initContainers")
    }

    val byGroup: Map<CatalogGroup, List<CatalogEntry>> by lazy { entries.groupBy { it.group } }

    // ---- unstructured-map access helpers (Gson materialises Map<String,Any?>) ----

    private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
    private fun Any?.asList(): List<Any?>? = this as? List<Any?>
    private fun Map<String, Any?>.str(vararg path: String): String? = at(*path) as? String
    private fun Map<String, Any?>.num(vararg path: String): Int? = (at(*path) as? Number)?.toInt()
    private fun Map<String, Any?>.flag(vararg path: String): Boolean? = at(*path) as? Boolean
    private fun Map<String, Any?>.mapAt(vararg path: String): Map<String, Any?>? = at(*path).asMap()
    private fun Map<String, Any?>.listAt(vararg path: String): List<Any?>? = at(*path).asList()

    private fun Map<String, Any?>.at(vararg path: String): Any? {
        var cur: Any? = this
        for (key in path) {
            cur = cur.asMap()?.get(key) ?: return null
        }
        return cur
    }

    private fun readyDesired(ready: Int?, desired: Int?): String = "${ready ?: 0}/${desired ?: 0} ready"

    // ---- the catalog (spec 04 table; frozen for this spec's acceptance run) ----

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            displayName = "Pods", kind = "Pod", group = CatalogGroup.WORKLOADS,
            apiVersion = "v1", resource = "pods", namespaced = true,
        ) { obj ->
            val phase = obj.str("status", "phase") ?: "Unknown"
            val desired = obj.listAt("spec", "containers")?.size ?: 0
            val ready = obj.listAt("status", "containerStatuses")
                ?.count { it.asMap()?.flag("ready") == true } ?: 0
            val node = obj.str("spec", "nodeName")
            StatusLine(phase, "$ready/$desired ready" + if (node != null) " · $node" else "")
        },
        CatalogEntry(
            displayName = "Deployments", kind = "Deployment", group = CatalogGroup.WORKLOADS,
            apiVersion = "apps/v1", resource = "deployments", namespaced = true,
        ) { obj ->
            StatusLine(readyDesired(obj.num("status", "readyReplicas"), obj.num("spec", "replicas")), null)
        },
        CatalogEntry(
            displayName = "StatefulSets", kind = "StatefulSet", group = CatalogGroup.WORKLOADS,
            apiVersion = "apps/v1", resource = "statefulsets", namespaced = true,
        ) { obj ->
            StatusLine(readyDesired(obj.num("status", "readyReplicas"), obj.num("spec", "replicas")), null)
        },
        CatalogEntry(
            displayName = "DaemonSets", kind = "DaemonSet", group = CatalogGroup.WORKLOADS,
            apiVersion = "apps/v1", resource = "daemonsets", namespaced = true,
        ) { obj ->
            StatusLine(
                readyDesired(obj.num("status", "numberReady"), obj.num("status", "desiredNumberScheduled")),
                null,
            )
        },
        CatalogEntry(
            displayName = "ReplicaSets", kind = "ReplicaSet", group = CatalogGroup.WORKLOADS,
            apiVersion = "apps/v1", resource = "replicasets", namespaced = true,
        ) { obj ->
            StatusLine(readyDesired(obj.num("status", "readyReplicas"), obj.num("spec", "replicas")), null)
        },
        CatalogEntry(
            displayName = "Jobs", kind = "Job", group = CatalogGroup.WORKLOADS,
            apiVersion = "batch/v1", resource = "jobs", namespaced = true,
        ) { obj ->
            val succeeded = obj.num("status", "succeeded") ?: 0
            val desired = obj.num("spec", "completions") ?: 1
            val conditions = obj.listAt("status", "conditions")
                ?.mapNotNull { it.asMap()?.str("type") }
                // Complete wins regardless of order: a retried-then-succeeded
                // Job legitimately carries both conditions.
            val condition = if (conditions?.contains("Complete") == true) "Complete"
                else if (conditions?.contains("Failed") == true) "Failed" else null
            StatusLine("$succeeded/$desired succeeded", condition)
        },
        CatalogEntry(
            displayName = "CronJobs", kind = "CronJob", group = CatalogGroup.WORKLOADS,
            apiVersion = "batch/v1", resource = "cronjobs", namespaced = true,
        ) { obj ->
            val schedule = obj.str("spec", "schedule") ?: "?"
            val secondary = when {
                obj.flag("spec", "suspend") == true -> "suspended"
                else -> obj.str("status", "lastScheduleTime")?.let { "last: $it" } ?: "never run"
            }
            StatusLine(schedule, secondary)
        },
        CatalogEntry(
            displayName = "Services", kind = "Service", group = CatalogGroup.NETWORK,
            apiVersion = "v1", resource = "services", namespaced = true,
        ) { obj ->
            val ports = obj.listAt("spec", "ports")
                ?.mapNotNull { it.asMap()?.let { p -> (p.num("port") ?: 0).toString() + "/" + (p.str("protocol") ?: "TCP") } }
                ?.joinToString(",")
            StatusLine(
                obj.str("spec", "type") ?: "?",
                listOfNotNull(obj.str("spec", "clusterIP"), ports?.takeIf { it.isNotEmpty() })
                    .joinToString(" · ").takeIf { it.isNotEmpty() },
            )
        },
        CatalogEntry(
            displayName = "ConfigMaps", kind = "ConfigMap", group = CatalogGroup.CONFIG,
            apiVersion = "v1", resource = "configmaps", namespaced = true,
        ) { obj ->
            val keys = (obj.mapAt("data")?.size ?: 0) + (obj.mapAt("binaryData")?.size ?: 0)
            StatusLine("$keys key${if (keys == 1) "" else "s"}", null)
        },
        CatalogEntry(
            displayName = "Secrets", kind = "Secret", group = CatalogGroup.CONFIG,
            apiVersion = "v1", resource = "secrets", namespaced = true,
        ) { obj ->
            val keys = (obj.mapAt("data")?.size ?: 0) + (obj.mapAt("stringData")?.size ?: 0)
            StatusLine(obj.str("type") ?: "Opaque", "$keys key${if (keys == 1) "" else "s"} (redacted)")
        },
        CatalogEntry(
            displayName = "PersistentVolumeClaims", kind = "PersistentVolumeClaim",
            group = CatalogGroup.STORAGE,
            apiVersion = "v1", resource = "persistentvolumeclaims", namespaced = true,
        ) { obj ->
            val size = obj.str("status", "capacity", "storage")
            val sc = obj.str("spec", "storageClassName")
            StatusLine(
                obj.str("status", "phase") ?: "Unknown",
                listOfNotNull(size, sc).joinToString(" · ").takeIf { it.isNotEmpty() },
            )
        },
        CatalogEntry(
            displayName = "Nodes", kind = "Node", group = CatalogGroup.CLUSTER,
            apiVersion = "v1", resource = "nodes", namespaced = false,
        ) { obj ->
            val ready = obj.listAt("status", "conditions")
                ?.firstOrNull { it.asMap()?.str("type") == "Ready" }
                ?.asMap()?.str("status")
            StatusLine(
                when (ready) { "True" -> "Ready"; "False" -> "NotReady"; else -> "Unknown" },
                obj.str("status", "nodeInfo", "kubeletVersion"),
            )
        },
        CatalogEntry(
            displayName = "Namespaces", kind = "Namespace", group = CatalogGroup.CLUSTER,
            apiVersion = "v1", resource = "namespaces", namespaced = false,
        ) { obj ->
            StatusLine(obj.str("status", "phase") ?: "Unknown", null)
        },
    )
}
