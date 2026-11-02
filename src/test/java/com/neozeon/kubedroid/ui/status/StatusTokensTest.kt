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

    private fun mapOfNotNullValues(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        pairs.toList().filter { it.second != null }.toMap()
}
