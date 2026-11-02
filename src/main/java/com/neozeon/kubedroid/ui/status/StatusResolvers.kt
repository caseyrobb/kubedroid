package com.neozeon.kubedroid.ui.status

/**
 * Spec 12 goal 1: map raw Kubernetes object JSON (the `Map<String, Any?>`
 * shape used by SummaryRows/the catalog extractor) onto [StatusToken]s.
 * Rendering code must go through these resolvers — never inline string
 * matching. Every path is total: missing/unknown shapes return the
 * Unknown token, never null, never a throw.
 */

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.child(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

private fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

/** Waiting reasons that override the pod phase for display. */
private val POD_REASON_OVERRIDES = setOf("CrashLoopBackOff", "ImagePullBackOff")

/**
 * Pod object -> token. Priority: waiting reason (CrashLoopBackOff /
 * ImagePullBackOff / Evicted-style `status.reason`) > phase.
 */
fun resolvePod(obj: Map<String, Any?>): StatusToken {
    val status = obj.child("status") ?: return PodStatus.UNKNOWN
    val reasons = buildList {
        status.list("containerStatuses")?.forEach { cs ->
            val r = cs.asObj()?.child("state")?.asObj()?.child("waiting")?.get("reason")
            if (r is String) add(r)
        }
        status.list("initContainerStatuses")?.forEach { cs ->
            val r = cs.asObj()?.child("state")?.asObj()?.child("waiting")?.get("reason")
            if (r is String) add(r)
        }
    }
    reasons.firstOrNull { it == "CrashLoopBackOff" }?.let { return PodStatus.CRASH_LOOP }
    reasons.firstOrNull { it == "ImagePullBackOff" || it == "ErrImagePull" }
        ?.let { return PodStatus.IMAGE_PULL }
    // status.reason covers Evicted, NodeAffinity, etc. — only meaningful
    // alongside a Failed phase (Pending pods surface scheduling issues via
    // conditions/message, not status.reason).
    if (status["phase"] == "Failed" && status["reason"] is String) {
        return PodStatus.FAILED
    }
    return PodStatus.resolve(status["phase"] as? String)
}

/**
 * Workload object (Deployment/StatefulSet/ReplicaSet/DaemonSet/Job/CronJob)
 * -> readiness token via `status.conditions`, falling back to
 * ready/desired replica comparison.
 */
fun resolveWorkload(obj: Map<String, Any?>): StatusToken {
    val kind = obj["kind"] as? String
    val spec = obj.child("spec")
    val status = obj.child("status")

    if (kind == "CronJob" && spec?.get("suspend") == true) {
        return WorkloadStatus.SUSPENDED
    }
    if (kind == "Job") {
        val succeeded = status?.int("succeeded") ?: 0
        val failed = status?.int("failed") ?: 0
        return when {
            failed > 0 -> WorkloadStatus.NOT_READY
            succeeded > 0 -> WorkloadStatus.READY
            else -> WorkloadStatus.PROGRESSING
        }
    }

    // Condition path: Ready=True/False, Progressing reason.
    val conditions = status?.list("conditions")?.mapNotNull { it.asObj() }.orEmpty()
    val ready = conditions.firstOrNull { it["type"] == "Ready" }
    val progressing = conditions.firstOrNull { it["type"] == "Progressing" }
    if (ready != null || progressing != null) {
        val readyTrue = ready?.get("status") == "True"
        val progressingReason = progressing?.get("reason") as? String
        return when {
            readyTrue -> WorkloadStatus.READY
            progressingReason == "Progressing" -> WorkloadStatus.PROGRESSING
            ready != null && !readyTrue -> {
                if (progressingReason == "Progressing") WorkloadStatus.PROGRESSING
                else WorkloadStatus.NOT_READY
            }
            progressingReason == "Progressing" -> WorkloadStatus.PROGRESSING
            else -> WorkloadStatus.UNKNOWN
        }
    }

    // Replica comparison fallback (some CRDs expose only counts).
    val desired = spec?.int("replicas")
        ?: status?.int("desiredNumberScheduled") // DaemonSet
        ?: return WorkloadStatus.UNKNOWN
    val readyCount = status?.int("readyReplicas")
        ?: status?.int("numberReady") // DaemonSet
        ?: status?.int("availableReplicas")
        ?: 0
    return when {
        desired == 0 -> WorkloadStatus.READY
        readyCount >= desired -> WorkloadStatus.READY
        readyCount > 0 -> WorkloadStatus.PROGRESSING
        else -> WorkloadStatus.NOT_READY
    }
}

/** Which fleet-health bucket a token counts toward (health bar, goal 3). */
fun healthBucket(token: StatusToken): HealthBucket = when (token) {
    PodStatus.FAILED, PodStatus.CRASH_LOOP, PodStatus.IMAGE_PULL -> HealthBucket.FAILED_RED
    PodStatus.PENDING -> HealthBucket.PENDING_AMBER
    WorkloadStatus.PROGRESSING -> HealthBucket.IN_PROGRESS_BLUE
    PodStatus.RUNNING, PodStatus.SUCCEEDED, WorkloadStatus.READY,
    ProbeStatus.REACHABLE,
    -> HealthBucket.OK_GREEN
    else -> HealthBucket.UNKNOWN_GRAY
}
