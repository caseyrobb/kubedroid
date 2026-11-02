package com.neozeon.kubedroid.ui.resources

import com.neozeon.kubedroid.data.model.ResourceSummary
import com.neozeon.kubedroid.ui.status.HealthBucket
import com.neozeon.kubedroid.ui.status.healthBucket

/**
 * Spec 12 goal 3/5: client-side sort + status filtering over the loaded
 * pages (Q3 decision: status chips are client-side; LABEL filtering is
 * server-side via `labelSelector`). Pure functions so the ViewModel logic
 * is unit-testable without Compose.
 */

/** Status-chip display labels (spec 12 goal 5). */
fun StatusFilter.chipLabel(): String = when (this) {
    StatusFilter.ALL -> "All"
    StatusFilter.HEALTHY -> "Healthy"
    StatusFilter.PENDING -> "Pending"
    StatusFilter.FAILED -> "Failing"
}

/** Sort-sheet display labels (spec 12 goal 5). */
fun ListSort.chipLabel(): String = when (this) {
    ListSort.NAME -> "Name"
    ListSort.NEWEST -> "Newest"
    ListSort.SEVERITY -> "Severity"
}

/** Kinds that carry a meaningful status token (pod-like + workloads). */
val STATUS_KINDS = setOf(
    "Pod", "Deployment", "StatefulSet", "ReplicaSet", "DaemonSet", "Job", "CronJob",
)

/** Sort options surfaced in the list's sort sheet (spec 12 goal 5). */
enum class ListSort { NAME, NEWEST, SEVERITY }

/** Status-chip filter over the loaded window (Q3: client-side). */
enum class StatusFilter { ALL, HEALTHY, PENDING, FAILED }

private val StatusFilter.buckets: Set<HealthBucket>
    get() = when (this) {
        StatusFilter.ALL -> emptySet()
        StatusFilter.HEALTHY -> setOf(HealthBucket.OK_GREEN)
        StatusFilter.PENDING -> setOf(HealthBucket.PENDING_AMBER, HealthBucket.IN_PROGRESS_BLUE)
        StatusFilter.FAILED -> setOf(HealthBucket.FAILED_RED)
    }

/** Search + status-chip filter, then sort. Pure, total. */
fun filterVisible(
    items: List<ResourceSummary>,
    search: String,
    statusFilter: StatusFilter,
    sort: ListSort,
): List<ResourceSummary> {
    var out = items
    if (search.isNotBlank()) {
        out = out.filter { s ->
            s.name.contains(search, ignoreCase = true) ||
                s.labels.values.any { it.contains(search, ignoreCase = true) }
        }
    }
    if (statusFilter != StatusFilter.ALL) {
        val wanted = statusFilter.buckets
        out = out.filter { healthBucket(it.statusToken) in wanted }
    }
    return when (sort) {
        ListSort.NAME -> out.sortedWith(
            compareBy({ it.namespace ?: "" }, { it.name.lowercase() }),
        )
        ListSort.NEWEST -> out.sortedByDescending { it.createdAt ?: "" }
        ListSort.SEVERITY -> out.sortedWith(
            compareBy({ it.statusToken.severity }, { it.name.lowercase() }),
        )
    }
}

/** Health-bar segment counts (spec 12 goal 3). */
fun healthCounts(items: List<ResourceSummary>): Map<HealthBucket, Int> {
    if (items.isEmpty()) return emptyMap()
    val out = LinkedHashMap<HealthBucket, Int>()
    for (s in items) {
        out.merge(healthBucket(s.statusToken), 1) { a, b -> a + b }
    }
    return out
}

/**
 * Label chips candidate: `k=v` strings over the loaded window, most
 * frequent first, capped. Tapping one applies a SERVER-side labelSelector
 * (Q3 decision), so counts are advisory only.
 */
fun topLabels(items: List<ResourceSummary>, cap: Int = 8): List<String> =
    items.flatMap { it.labels.entries }
        .groupingBy { "${it.key}=${it.value}" }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(cap)
        .map { it.key }

/** AND-joined `labelSelector` for the server-side query; null when none. */
fun labelSelectorOf(filters: Set<String>): String? =
    filters.sorted().joinToString(",").takeIf { it.isNotBlank() }
