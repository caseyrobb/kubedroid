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
fun resolveWorkload(obj: Map<String, Any?>, fallbackKind: String? = null): StatusToken {
    // Same fallback as resolveStatus: LIST items carry no `kind`, so
    // Job/CronJob-specific branches would silently skip and fall into the
    // replica-count path (which has no counts for them) -> Unknown.
    val kind = (obj["kind"] as? String)?.takeIf { it.isNotEmpty() } ?: fallbackKind
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

    // Condition path: Ready=True/False, Progressing reason. When neither
    // condition is decisive (real Deployments ship Available=True +
    // Progressing=True/NewReplicaSetAvailable and NO Ready condition on
    // list/get), do NOT bail to Unknown — fall through to the Available +
    // replica checks below (2026-11-03 field regression).
    val conditions = status?.list("conditions")?.mapNotNull { it.asObj() }.orEmpty()
    val ready = conditions.firstOrNull { it["type"] == "Ready" }
    val progressing = conditions.firstOrNull { it["type"] == "Progressing" }
    if (ready != null || progressing != null) {
        val readyTrue = ready?.get("status") == "True"
        val progressingReason = progressing?.get("reason") as? String
        when {
            readyTrue -> return WorkloadStatus.READY
            progressingReason == "Progressing" -> return WorkloadStatus.PROGRESSING
            ready != null && !readyTrue ->
                return if (progressingReason == "Progressing") WorkloadStatus.PROGRESSING
                else WorkloadStatus.NOT_READY
            else -> Unit // Progressing=True with a terminal reason: fall through
        }
    }

    // Available=True is the Deployment/StatefulSet health condition
    // (kubectl's DEPLOYMENTS column is Available + Progressing).
    if (conditions.any { it["type"] == "Available" && it["status"] == "True" }) {
        return WorkloadStatus.READY
    }

    // Replica comparison fallback (also covers StatefulSet/DaemonSet counts).
    val desired = spec?.int("replicas")
        ?: status?.int("desiredNumberScheduled") // DaemonSet
        ?: return if (kind == "CronJob") {
            // CronJob has no replica semantics: derive from its own
            // conditions — Active=True means a Job is currently running;
            // any (Suspended=False etc.) condition set with the cron
            // controller synced means scheduled/idle.
            when {
                conditions.any { it["type"] == "Active" && it["status"] == "True" } ->
                    WorkloadStatus.PROGRESSING
                conditions.isNotEmpty() -> WorkloadStatus.READY
                else -> WorkloadStatus.UNKNOWN
            }
        } else {
            WorkloadStatus.UNKNOWN
        }
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

/** Kinds that get the workload readiness path in [resolveStatus]. */
private val WORKLOAD_KINDS = setOf(
    "Deployment", "StatefulSet", "ReplicaSet", "DaemonSet", "Job", "CronJob",
)

/**
 * Kind dispatcher for list rows (spec 12 stage 3): pods take the phase
 * path, known workloads the readiness path, everything else Unknown.
 *
 * `fallbackKind` exists because the apiserver **omits `kind` on list
 * items** (their `apiVersion` is frequently empty too) — only the list
 * envelope carries the collection's kind, which the caller knows from its
 * [com.neozeon.kubedroid.data.kubernetes.CatalogEntry]. A single GET of the
 * item does carry `kind`, which is why the detail screen resolved status
 * correctly while every list row read Unknown. Detail callers leave
 * [fallbackKind] null; list callers must pass the catalog kind.
 */
fun resolveStatus(obj: Map<String, Any?>, fallbackKind: String? = null): StatusToken =
    when (val kind = (obj["kind"] as? String)?.takeIf { it.isNotEmpty() } ?: fallbackKind) {
        "Pod" -> resolvePod(obj)
        in WORKLOAD_KINDS -> resolveWorkload(obj, kind)
        else -> WorkloadStatus.UNKNOWN
    }

/**
 * `status.conditions[]` -> pills (spec 12 goal 2, detail SUMMARY): one
 * pill per condition, label = condition type, True takes the token's
 * good/red semantics. Total: malformed entries are skipped, empty list
 * when there are none.
 */
fun conditionChips(obj: Map<String, Any?>): List<StatusToken> =
    obj.child("status")?.list("conditions")?.mapNotNull { raw ->
        val c = raw.asObj() ?: return@mapNotNull null
        val type = c["type"] as? String ?: return@mapNotNull null
        when (c["status"] as? String) {
            "True" -> WorkloadStatus.READY.copy(label = type)
            "False" -> WorkloadStatus.NOT_READY.copy(label = type)
            else -> WorkloadStatus.UNKNOWN.copy(label = type)
        }
    }.orEmpty()

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
