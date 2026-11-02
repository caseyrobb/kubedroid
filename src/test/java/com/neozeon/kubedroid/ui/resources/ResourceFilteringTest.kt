package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.data.model.ResourceSummary
import com.neozeon.kubedroid.ui.status.PodStatus
import com.neozeon.kubedroid.ui.status.WorkloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 12 stage 3: pure sort/status-filter/label helpers. */
class ResourceFilteringTest {

    private fun summary(
        name: String,
        ns: String = "web",
        token: com.neozeon.kubedroid.ui.status.StatusToken = PodStatus.RUNNING,
        labels: Map<String, String> = emptyMap(),
        created: String? = "2026-01-01T00:00:00Z",
    ) = ResourceSummary(
        apiVersion = "v1", kind = "Pod", name = name, namespace = ns,
        labels = labels, primary = token.label, secondary = null,
        createdAt = created, statusToken = token,
    )

    private val mixed = listOf(
        summary("zeta", token = PodStatus.PENDING),
        summary("alpha", token = PodStatus.CRASH_LOOP),
        summary("Mid", token = PodStatus.RUNNING, created = "2026-01-03T00:00:00Z"),
        summary("beta", token = WorkloadStatus.PROGRESSING, created = "2026-01-02T00:00:00Z"),
    )

    @Test fun nameSortCaseInsensitiveWithinNamespace() {
        assertEquals(
            listOf("alpha", "beta", "Mid", "zeta"),
            filterVisible(mixed, "", StatusFilter.ALL, ListSort.NAME).map { it.name },
        )
    }

    @Test fun nameSortGroupsNamespaceFirst() {
        val items = listOf(summary("a", ns = "z"), summary("b", ns = "a"))
        assertEquals(listOf("b", "a"), filterVisible(items, "", StatusFilter.ALL, ListSort.NAME).map { it.name })
    }

    @Test fun newestSortDescending() {
        assertEquals(
            listOf("Mid", "beta", "zeta", "alpha"),
            filterVisible(mixed, "", StatusFilter.ALL, ListSort.NEWEST).map { it.name },
        )
    }

    @Test fun newestSortNullsLast() {
        val items = listOf(summary("old"), summary("new", created = null))
        assertEquals(listOf("old", "new"), filterVisible(items, "", StatusFilter.ALL, ListSort.NEWEST).map { it.name })
    }

    @Test fun severitySortProblemsFirstThenName() {
        // severity: alpha CRASH_LOOP=0, beta ImagePull=1, zeta Pending=2, Mid Running=4
        assertEquals(
            listOf("alpha", "beta", "zeta", "Mid"),
            filterVisible(mixed, "", StatusFilter.ALL, ListSort.SEVERITY).map { it.name },
        )
    }

    @Test fun statusFiltersMapToHealthBuckets() {
        assertEquals(
            listOf("Mid"),
            filterVisible(mixed, "", StatusFilter.HEALTHY, ListSort.NAME).map { it.name },
        )
        assertEquals(
            listOf("beta", "zeta"),
            filterVisible(mixed, "", StatusFilter.PENDING, ListSort.NAME).map { it.name },
        )
        assertEquals(
            listOf("alpha"),
            filterVisible(mixed, "", StatusFilter.FAILED, ListSort.NAME).map { it.name },
        )
    }

    @Test fun searchAndStatusCompose() {
        val items = listOf(
            summary("web-1", token = PodStatus.RUNNING, labels = mapOf("app" to "web")),
            summary("db-1", token = PodStatus.FAILED, labels = mapOf("app" to "web")),
        )
        assertEquals(
            listOf("db-1"),
            filterVisible(items, "web", StatusFilter.FAILED, ListSort.NAME).map { it.name },
        )
    }

    @Test fun healthCountsBucketTotals() {
        val counts = healthCounts(mixed)
        assertEquals(1, counts[com.neozeon.kubedroid.ui.status.HealthBucket.FAILED_RED])
        assertEquals(1, counts[com.neozeon.kubedroid.ui.status.HealthBucket.PENDING_AMBER])
        assertEquals(1, counts[com.neozeon.kubedroid.ui.status.HealthBucket.IN_PROGRESS_BLUE])
        assertEquals(1, counts[com.neozeon.kubedroid.ui.status.HealthBucket.OK_GREEN])
        assertEquals(emptyMap<Any, Any>(), healthCounts(emptyList()))
    }

    @Test fun topLabelsFrequencyThenAlphaCapped() {
        val items = listOf(
            summary("a", labels = mapOf("app" to "web", "tier" to "front")),
            summary("b", labels = mapOf("app" to "web")),
            summary("c", labels = mapOf("app" to "db")),
        )
        assertEquals(listOf("app=web", "app=db", "tier=front"), topLabels(items))
        assertEquals(listOf("app=web"), topLabels(items, cap = 1))
    }

    @Test fun labelSelectorJoinsAndSorts() {
        assertNull(labelSelectorOf(emptySet()))
        assertEquals("a=1,b=2", labelSelectorOf(setOf("b=2", "a=1")))
    }

    @Test fun statusKindsCoverPodAndWorkloads() {
        assertEquals(
            setOf("Pod", "Deployment", "StatefulSet", "ReplicaSet", "DaemonSet", "Job", "CronJob"),
            STATUS_KINDS,
        )
    }
}
