package com.neozeon.kubedroid.ui.status

import com.neozeon.kubedroid.data.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 12 stage 1: token tables, resolvers, and health bucketing. */
class StatusTokensTest {

    // ---- tables ----------------------------------------------------------

    @Test fun containerAndOutlineFollowUpstreamAlphaRecipe() {
        val t = PodStatus.RUNNING
        assertEquals(t.color.copy(alpha = 0.14f), t.containerColor)
        assertEquals(t.color.copy(alpha = 0.40f), t.outlineColor)
    }

    @Test fun severityOrdersProblemsFirst() {
        val sorted = listOf(
            PodStatus.RUNNING, PodStatus.PENDING, PodStatus.FAILED,
            PodStatus.UNKNOWN, PodStatus.CRASH_LOOP, PodStatus.SUCCEEDED,
        ).sortedBy { it.severity }.map { it.label }
        assertEquals(
            listOf("Failed", "CrashLoopBackOff", "Pending", "Unknown", "Running", "Succeeded"),
            sorted.take(5) + sorted.takeLast(1),
        )
    }

    @Test fun resolveFallsBackToUnknown() {
        assertSame(PodStatus.UNKNOWN, PodStatus.resolve(null))
        assertSame(PodStatus.UNKNOWN, PodStatus.resolve("Teleporting"))
        assertSame(WorkloadStatus.UNKNOWN, WorkloadStatus.resolve(null))
        assertSame(WorkloadStatus.UNKNOWN, WorkloadStatus.resolve("?"))
    }

    @Test fun probeMapsConnectionStatus() {
        assertSame(
            ProbeStatus.REACHABLE,
            ProbeStatus.resolve(ConnectionStatus.Reachable("v1.36.4")),
        )
        assertSame(
            ProbeStatus.UNREACHABLE,
            ProbeStatus.resolve(ConnectionStatus.Unreachable("timeout")),
        )
        assertSame(ProbeStatus.UNVALIDATED, ProbeStatus.resolve(ConnectionStatus.Unvalidated))
    }

    // ---- pod resolver ----------------------------------------------------

    private fun pod(vararg pairs: Pair<String, Any?>) = mapOf("kind" to "Pod", "status" to mapOf(*pairs))

    @Test fun podPhaseMapping() {
        assertSame(PodStatus.RUNNING, resolvePod(pod("phase" to "Running")))
        assertSame(PodStatus.PENDING, resolvePod(pod("phase" to "Pending")))
        assertSame(PodStatus.SUCCEEDED, resolvePod(pod("phase" to "Succeeded")))
        assertSame(PodStatus.FAILED, resolvePod(pod("phase" to "Failed")))
        assertSame(PodStatus.UNKNOWN, resolvePod(pod("phase" to "Weird")))
        assertSame(PodStatus.UNKNOWN, resolvePod(emptyMap()))
        assertSame(PodStatus.UNKNOWN, resolvePod(mapOf("status" to "not-a-map")))
    }

    @Test fun waitingReasonOverridesPhase() {
        val obj = pod(
            "phase" to "Running",
            "containerStatuses" to listOf(
                mapOf("state" to mapOf("waiting" to mapOf("reason" to "CrashLoopBackOff"))),
            ),
        )
        assertSame(PodStatus.CRASH_LOOP, resolvePod(obj))
    }

    @Test fun initContainerImagePullCounts() {
        val obj = pod(
            "phase" to "Pending",
            "initContainerStatuses" to listOf(
                mapOf("state" to mapOf("waiting" to mapOf("reason" to "ImagePullBackOff"))),
            ),
        )
        assertSame(PodStatus.IMAGE_PULL, resolvePod(obj))
    }

    @Test fun evictedReasonIsFailed() {
        assertSame(PodStatus.FAILED, resolvePod(pod("phase" to "Failed", "reason" to "Evicted")))
    }

    @Test fun malformedContainerStatusesDoNotThrow() {
        val obj = pod(
            "phase" to "Running",
            "containerStatuses" to listOf("junk", 42, null),
        )
        assertSame(PodStatus.RUNNING, resolvePod(obj))
    }

    // ---- workload resolver -------------------------------------------------

    private fun workload(kind: String, vararg pairs: Pair<String, Any?>) =
        mapOf("kind" to kind, *pairs)

    @Test fun readyCondition() {
        val obj = workload(
            "Deployment",
            "status" to mapOf(
                "conditions" to listOf(mapOf("type" to "Ready", "status" to "True")),
            ),
        )
        assertSame(WorkloadStatus.READY, resolveWorkload(obj))
    }

    @Test fun notReadyWithProgressingReasonIsProgressing() {
        val obj = workload(
            "Deployment",
            "status" to mapOf(
                "conditions" to listOf(
                    mapOf("type" to "Ready", "status" to "False"),
                    mapOf("type" to "Progressing", "status" to "True", "reason" to "Progressing"),
                ),
            ),
        )
        assertSame(WorkloadStatus.PROGRESSING, resolveWorkload(obj))
    }

    @Test fun notReadyWithoutProgressingIsNotReady() {
        val obj = workload(
            "StatefulSet",
            "status" to mapOf(
                "conditions" to listOf(mapOf("type" to "Ready", "status" to "False")),
            ),
        )
        assertSame(WorkloadStatus.NOT_READY, resolveWorkload(obj))
    }

    @Test fun replicaFallback() {
        fun dep(desired: Int?, ready: Int?) = workload(
            "Deployment",
            "spec" to mapOfNotNullValues("replicas" to desired),
            "status" to mapOfNotNullValues("readyReplicas" to ready),
        )
        assertSame(WorkloadStatus.READY, resolveWorkload(dep(3, 3)))
        assertSame(WorkloadStatus.READY, resolveWorkload(dep(0, 0)))
        assertSame(WorkloadStatus.PROGRESSING, resolveWorkload(dep(3, 1)))
        assertSame(WorkloadStatus.NOT_READY, resolveWorkload(dep(3, 0)))
        assertSame(WorkloadStatus.UNKNOWN, resolveWorkload(dep(null, 2)))
    }

    @Test fun daemonSetUsesDesiredScheduled() {
        val obj = workload(
            "DaemonSet",
            "status" to mapOf("desiredNumberScheduled" to 6, "numberReady" to 6),
        )
        assertSame(WorkloadStatus.READY, resolveWorkload(obj))
    }

    @Test fun cronJobSuspended() {
        val obj = workload("CronJob", "spec" to mapOf("suspend" to true))
        assertSame(WorkloadStatus.SUSPENDED, resolveWorkload(obj))
    }

    @Test fun jobPhases() {
        assertSame(
            WorkloadStatus.READY,
            resolveWorkload(workload("Job", "status" to mapOf("succeeded" to 1))),
        )
        assertSame(
            WorkloadStatus.NOT_READY,
            resolveWorkload(workload("Job", "status" to mapOf("failed" to 1))),
        )
        assertSame(WorkloadStatus.PROGRESSING, resolveWorkload(workload("Job")))
    }

    // ---- list-shaped workloads (no kind; real-world condition shapes) ----
    // Captured 2026-11-03 from a live cluster's raw LIST wire JSON — the
    // exact shapes that rendered Unknown in the field.

    @Test fun deploymentWithoutReadyConditionUsesAvailable() {
        // Live shape: Available=True/MinimumReplicasAvailable +
        // Progressing=True/NewReplicaSetAvailable, NO Ready condition.
        val obj = mapOf(
            "metadata" to mapOf("name" to "repo-server"),
            "spec" to mapOf("replicas" to 1.0),
            "status" to mapOf(
                "readyReplicas" to 1.0, "availableReplicas" to 1.0, "replicas" to 1.0,
                "conditions" to listOf(
                    mapOf("type" to "Available", "status" to "True", "reason" to "MinimumReplicasAvailable"),
                    mapOf("type" to "Progressing", "status" to "True", "reason" to "NewReplicaSetAvailable"),
                ),
            ),
        )
        assertSame(WorkloadStatus.READY, resolveStatus(obj, "Deployment"))
    }

    @Test fun deploymentAvailableFalseFallsToReplicaCounts() {
        val obj = mapOf(
            "spec" to mapOf("replicas" to 2.0),
            "status" to mapOf(
                "readyReplicas" to 0.0,
                "conditions" to listOf(
                    mapOf("type" to "Available", "status" to "False", "reason" to "MinimumReplicasUnavailable"),
                    mapOf("type" to "Progressing", "status" to "True", "reason" to "ReplicaSetUpdated"),
                ),
            ),
        )
        assertSame(WorkloadStatus.NOT_READY, resolveStatus(obj, "Deployment"))
    }

    private fun kindlessJob(status: Map<String, Any?>) = mapOf(
        "metadata" to mapOf("name" to "migrate"),
        "spec" to mapOf("parallelism" to 1.0, "completions" to 1.0),
        "status" to status,
    )

    @Test fun jobBranchesWorkWithoutKind() {
        assertSame(WorkloadStatus.READY, resolveStatus(kindlessJob(mapOf("succeeded" to 1.0)), "Job"))
        assertSame(WorkloadStatus.NOT_READY, resolveStatus(kindlessJob(mapOf("failed" to 1.0)), "Job"))
        assertSame(WorkloadStatus.PROGRESSING, resolveStatus(kindlessJob(emptyMap()), "Job"))
    }

    @Test fun cronJobBranchesWorkWithoutKind() {
        val suspended = mapOf("spec" to mapOf("suspend" to true))
        assertSame(WorkloadStatus.SUSPENDED, resolveStatus(suspended, "CronJob"))
        val running = mapOf(
            "spec" to mapOf("schedule" to "@daily"),
            "status" to mapOf(
                "conditions" to listOf(
                    mapOf("type" to "Suspended", "status" to "False"),
                    mapOf("type" to "Active", "status" to "True"),
                ),
            ),
        )
        assertSame(WorkloadStatus.PROGRESSING, resolveStatus(running, "CronJob"))
        val idle = mapOf(
            "spec" to mapOf("schedule" to "@daily"),
            "status" to mapOf(
                "conditions" to listOf(mapOf("type" to "Suspended", "status" to "False")),
            ),
        )
        assertSame(WorkloadStatus.READY, resolveStatus(idle, "CronJob"))
        // Never synced by the controller: no conditions, no timestamps ->
        // Unknown.
        assertSame(
            WorkloadStatus.UNKNOWN,
            resolveStatus(mapOf("spec" to mapOf("schedule" to "@daily")), "CronJob"),
        )
    }

    @Test fun cronJobReadyWithRealStatusNoConditions() {
        // chaos `set-es-replicas` raw LIST capture 2026-11-03: the batch/v1
        // controller leaves conditions EMPTY by default (CronJobStatusConditions
        // gate OFF) — healthy status is just the two timestamps. Requiring
        // conditions here rendered every real CronJob Unknown in the field.
        val live = mapOf(
            "metadata" to mapOf("name" to "set-es-replicas"),
            "spec" to mapOf("schedule" to "0 23 * * *", "suspend" to false),
            "status" to mapOf(
                "lastScheduleTime" to "2026-11-03T23:00:00Z",
                "lastSuccessfulTime" to "2026-11-03T23:00:10Z",
            ),
        )
        assertSame(WorkloadStatus.READY, resolveStatus(live, "CronJob"))
    }

    @Test fun cronJobProgressingWhenActiveJobsListed() {
        // In-flight schedule = non-empty status.active ARRAY (there is no
        // Active condition by default).
        val inflight = mapOf(
            "spec" to mapOf("schedule" to "@hourly", "suspend" to false),
            "status" to mapOf(
                "active" to listOf(mapOf("name" to "set-es-replicas-2lq4k")),
                "lastScheduleTime" to "2026-11-03T23:00:00Z",
            ),
        )
        assertSame(WorkloadStatus.PROGRESSING, resolveStatus(inflight, "CronJob"))
    }

    // ---- health bucketing --------------------------------------------------

    @Test fun buckets() {
        assertEquals(HealthBucket.FAILED_RED, healthBucket(PodStatus.CRASH_LOOP))
        assertEquals(HealthBucket.FAILED_RED, healthBucket(PodStatus.IMAGE_PULL))
        assertEquals(HealthBucket.PENDING_AMBER, healthBucket(PodStatus.PENDING))
        assertEquals(HealthBucket.IN_PROGRESS_BLUE, healthBucket(WorkloadStatus.PROGRESSING))
        assertEquals(HealthBucket.OK_GREEN, healthBucket(PodStatus.RUNNING))
        assertEquals(HealthBucket.OK_GREEN, healthBucket(WorkloadStatus.READY))
        assertEquals(HealthBucket.UNKNOWN_GRAY, healthBucket(PodStatus.UNKNOWN))
    }

    // ---- condition chips (spec 12 stage 4) --------------------------------

    @Test fun conditionChipsMapTypeAndStatus() {
        val obj = mapOf(
            "status" to mapOf(
                "conditions" to listOf(
                    mapOf("type" to "Ready", "status" to "True"),
                    mapOf("type" to "Degraded", "status" to "False"),
                    mapOf("type" to "Available"),
                    "garbage",
                    mapOf("status" to "True"),
                ),
            ),
        )
        val chips = conditionChips(obj)
        assertEquals(listOf("Ready", "Degraded", "Available"), chips.map { it.label })
        // Color is a value class — compare by equality, not identity.
        assertEquals(WorkloadStatus.READY.color, chips[0].color)
        assertEquals(WorkloadStatus.NOT_READY.color, chips[1].color)
        assertEquals(WorkloadStatus.UNKNOWN.color, chips[2].color)
    }

    @Test fun conditionChipsEmptyWhenNoConditions() {
        assertEquals(emptyList<StatusToken>(), conditionChips(mapOf("status" to "junk")))
        assertEquals(emptyList<StatusToken>(), conditionChips(emptyMap()))
    }

    // ---- kind dispatcher (list rows: apiserver omits kind on items) ------

    /** A list-shaped pod: no `kind`, no `apiVersion` — as the apiserver sends it. */
    private val kindlessPod = mapOf(
        "apiVersion" to "v1",
        "metadata" to mapOf("name" to "web-1"),
        "status" to mapOf("phase" to "Running"),
    )

    @Test fun resolveStatusUsesFallbackKindWhenItemOmitsKind() {
        // Real regression: LIST items carry no `kind`, so every row read
        // Unknown until the catalog entry's kind was threaded through.
        assertSame(PodStatus.RUNNING, resolveStatus(kindlessPod, "Pod"))
        assertSame(WorkloadStatus.UNKNOWN, resolveStatus(kindlessPod))
    }

    @Test fun resolveStatusTreatsEmptyKindAsMissing() {
        val obj = kindlessPod + ("kind" to "")
        assertSame(PodStatus.RUNNING, resolveStatus(obj, "Pod"))
    }

    @Test fun resolveStatusItemKindWinsOverFallback() {
        val explicit = kindlessPod + ("kind" to "Pod")
        assertSame(PodStatus.RUNNING, resolveStatus(explicit, "Deployment"))
    }

    @Test fun resolveStatusWorkloadViaFallback() {
        val obj = mapOf(
            "metadata" to mapOf("name" to "web"),
            "spec" to mapOf("replicas" to 2.0),
            "status" to mapOf("readyReplicas" to 2.0),
        )
        assertSame(WorkloadStatus.READY, resolveStatus(obj, "Deployment"))
        assertSame(WorkloadStatus.UNKNOWN, resolveStatus(obj, "Widget"))
    }

    private fun mapOfNotNullValues(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        pairs.toList().filter { it.second != null }.toMap()
}
