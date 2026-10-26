package com.neozeon.kubedroid.data.kubernetes

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Spec 04 stage 2 Testing: one case per catalog row asserting exact URL
 * shapes (all-namespaces, scoped, cluster-scoped, single object) and the
 * status extractors fed real API-server JSON shapes.
 */
class ResourceCatalogTest {

    private val gson = Gson()

    private fun entry(displayName: String): CatalogEntry =
        ResourceCatalog.entries.first { it.displayName == displayName }

    private fun json(text: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST") (gson.fromJson(text, Map::class.java) as Map<String, Any?>)

    @Test fun coreAllNamespacesPath() {
        assertEquals("/api/v1/pods", ResourceCatalog.listPath(entry("Pods"), null))
    }

    @Test fun coreScopedPath() {
        assertEquals(
            "/api/v1/namespaces/kube-system/pods",
            ResourceCatalog.listPath(entry("Pods"), "kube-system"),
        )
    }

    @Test fun groupedPaths() {
        assertEquals("/apis/apps/v1/deployments", ResourceCatalog.listPath(entry("Deployments"), null))
        assertEquals(
            "/apis/batch/v1/namespaces/teams/cronjobs",
            ResourceCatalog.listPath(entry("CronJobs"), "teams"),
        )
    }

    @Test fun clusterScopedPathsIgnoreNamespace() {
        assertEquals("/api/v1/nodes", ResourceCatalog.listPath(entry("Nodes"), null))
        assertEquals("/api/v1/namespaces", ResourceCatalog.listPath(entry("Namespaces"), null))
        assertThrows(IllegalArgumentException::class.java) {
            ResourceCatalog.listPath(entry("Nodes"), "kube-system")
        }
    }

    @Test fun objectPaths() {
        assertEquals(
            "/apis/apps/v1/namespaces/web/deployments/nginx",
            ResourceCatalog.getPath(entry("Deployments"), "web", "nginx"),
        )
        assertEquals("/api/v1/nodes/node-a", ResourceCatalog.getPath(entry("Nodes"), null, "node-a"))
    }

    @Test fun findLocatesRowsByApiVersionAndResource() {
        assertEquals("DaemonSet", ResourceCatalog.find("apps/v1", "daemonsets")?.kind)
        assertEquals("PersistentVolumeClaim", ResourceCatalog.find("v1", "persistentvolumeclaims")?.kind)
        assertNull(ResourceCatalog.find("apps/v1", "pods"))
    }

    @Test fun fullTablePathsEveryRow() {
        // Every row must produce well-formed paths — guards typos in the table.
        ResourceCatalog.entries.forEach { e ->
            val all = ResourceCatalog.listPath(e, null)
            assertEquals(true, all.startsWith("/api/v1/") || all.startsWith("/apis/"))
            assertEquals(true, all.endsWith("/" + e.resource))
            if (e.namespaced) {
                assertEquals(true, ResourceCatalog.listPath(e, "ns1").contains("/namespaces/ns1/"))
            }
        }
    }

    // ---- status extractors ----

    @Test fun podPendingCountsContainersWithoutStatuses() {
        val obj = json(
            """{"spec":{"containers":[{},{},{}]}}""",
        )
        val line = entry("Pods").statusExtractor(obj)
        assertEquals("Unknown", line.primary)
        assertEquals("0/3 ready", line.secondary)
    }

    @Test fun podRunningReadyWithNode() {
        val obj = json(
            """{"spec":{"nodeName":"w1","containers":[{},{}]},
               "status":{"phase":"Running","containerStatuses":[
                 {"ready":true},{"ready":false}]}}""",
        )
        val line = entry("Pods").statusExtractor(obj)
        assertEquals("Running", line.primary)
        assertEquals("1/2 ready · w1", line.secondary)
    }

    @Test fun deploymentPartialReady() {
        val line = entry("Deployments").statusExtractor(
            json("""{"spec":{"replicas":3},"status":{"readyReplicas":2}}"""),
        )
        assertEquals("2/3 ready", line.primary)
    }

    @Test fun daemonSetCounts() {
        val line = entry("DaemonSets").statusExtractor(
            json("""{"status":{"numberReady":5,"desiredNumberScheduled":6}}"""),
        )
        assertEquals("5/6 ready", line.primary)
    }

    @Test fun jobCompleteCondition() {
        val line = entry("Jobs").statusExtractor(
            json("""{"spec":{"completions":4},"status":{"succeeded":4,
                 "conditions":[{"type":"Failed"},{"type":"Complete"}]}}"""),
        )
        assertEquals("4/4 succeeded", line.primary)
        assertEquals("Complete", line.secondary)
    }

    @Test fun cronJobSuspended() {
        val line = entry("CronJobs").statusExtractor(
            json("""{"spec":{"schedule":"*/5 * * * *","suspend":true}}"""),
        )
        assertEquals("*/5 * * * *", line.primary)
        assertEquals("suspended", line.secondary)
    }

    @Test fun serviceTypePorts() {
        val line = entry("Services").statusExtractor(
            json("""{"spec":{"type":"ClusterIP","clusterIP":"10.0.0.7",
                 "ports":[{"port":80,"protocol":"TCP"},{"port":443,"protocol":"TCP"}]}}"""),
        )
        assertEquals("ClusterIP", line.primary)
        assertEquals("10.0.0.7 · 80/TCP,443/TCP", line.secondary)
    }

    @Test fun configMapKeyCountSingular() {
        val line = entry("ConfigMaps").statusExtractor(json("""{"data":{"a":"1"}}"""))
        assertEquals("1 key", line.primary)
    }

    @Test fun secretRowIsRedacted() {
        val line = entry("Secrets").statusExtractor(json("""{"type":"Opaque","data":{"a":"Yw=="}}"""))
        assertEquals("Opaque", line.primary)
        assertEquals("1 key (redacted)", line.secondary)
    }

    @Test fun nodeNotReady() {
        val line = entry("Nodes").statusExtractor(
            json("""{"status":{"conditions":[{"type":"Ready","status":"False"}],
                 "nodeInfo":{"kubeletVersion":"v1.36.4"}}}"""),
        )
        assertEquals("NotReady", line.primary)
        assertEquals("v1.36.4", line.secondary)
    }

    @Test fun pvcBoundWithCapacity() {
        val line = entry("PersistentVolumeClaims").statusExtractor(
            json("""{"spec":{"storageClassName":"longhorn"},
                 "status":{"phase":"Bound","capacity":{"storage":"10Gi"}}}"""),
        )
        assertEquals("Bound", line.primary)
        assertEquals("10Gi · longhorn", line.secondary)
    }

    // ---- spec 05: log subresource paths ----

    @Test fun logPathShape() {
        assertEquals(
            "/api/v1/namespaces/web/pods/web-1/log",
            ResourceCatalog.logPath(entry("Pods"), "web", "web-1"),
        )
    }

    @Test fun logPathPercentEncodesSpecialSegments() {
        // Path-segment safety: names can never inject "/" or non-ASCII bytes
        // into the URL (spec 05 Testing).
        assertEquals(
            "/api/v1/namespaces/we%20b/pods/we%2Fd%201/log",
            ResourceCatalog.logPath(entry("Pods"), "we b", "we/d 1"),
        )
        assertEquals(
            "/api/v1/namespaces/t%C3%A9st/pods/p1/log",
            ResourceCatalog.logPath(entry("Pods"), "tést", "p1"),
        )
        // Unreserved characters stay readable.
        assertEquals(
            "/api/v1/namespaces/kube-system/pods/web-1_2~3/log",
            ResourceCatalog.logPath(entry("Pods"), "kube-system", "web-1_2~3"),
        )
    }
}
