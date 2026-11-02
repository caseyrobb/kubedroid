package com.neozeon.kubedroid.ui.status

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.neozeon.kubedroid.data.model.ConnectionStatus

/**
 * Spec 12: semantic status tokens — the single source of truth for how every
 * state the app renders looks and sorts. Patterns borrowed from
 * argoproj-labs/mobile-for-argocd `lib/status.ts` (color + translucent fill
 * + translucent border + glyph + severity order), expressed through fixed
 * semantic hues so dynamic color (spec 08) is unaffected elsewhere.
 */
data class StatusToken(
    val label: String,
    val color: Color,
    val icon: ImageVector,
    /** 0 = most severe; sorting ascending surfaces problems first. */
    val severity: Int,
) {
    val containerColor: Color get() = color.copy(alpha = 0.14f)
    val outlineColor: Color get() = color.copy(alpha = 0.40f)
}

// Semantic hues (Q1 decision 2026-10-29: hues only, no fixed dark skin).
// Chosen for AA-ish contrast on both M3 light surfaces and dark surfaces.
private val Green = Color(0xFF1B8A5A) // good
private val Amber = Color(0xFFB8860B) // drift / pending
private val Red = Color(0xFFD32F2F) // failure
private val Blue = Color(0xFF1976D2) // in-progress / informational
private val Purple = Color(0xFF7B4FD8) // suspended
private val Gray = Color(0xFF8A8F98) // unknown

/** Pod phases + well-known waiting reasons, severity-ordered. */
object PodStatus {
    val RUNNING = StatusToken("Running", Green, Icons.Filled.CheckCircle, 4)
    val SUCCEEDED = StatusToken("Succeeded", Green, Icons.Filled.CheckCircle, 5)
    val PENDING = StatusToken("Pending", Amber, Icons.Filled.Warning, 2)
    val FAILED = StatusToken("Failed", Red, Icons.Filled.Warning, 0)
    val CRASH_LOOP = StatusToken("CrashLoopBackOff", Red, Icons.Filled.Warning, 0)
    val IMAGE_PULL = StatusToken("ImagePullBackOff", Red, Icons.Filled.Warning, 1)
    val UNKNOWN = StatusToken("Unknown", Gray, Icons.Filled.Info, 3)

    private val byName: Map<String, StatusToken> = listOf(
        RUNNING, SUCCEEDED, PENDING, FAILED, CRASH_LOOP, IMAGE_PULL,
    ).associateBy { it.label }

    fun resolve(name: String?): StatusToken =
        name?.let { byName[it] } ?: UNKNOWN
}

/** Workload readiness (Deployments/StatefulSets/DaemonSets/ReplicaSets). */
object WorkloadStatus {
    val READY = StatusToken("Ready", Green, Icons.Filled.CheckCircle, 3)
    val PROGRESSING = StatusToken("Progressing", Blue, Icons.Filled.Refresh, 2)
    val NOT_READY = StatusToken("NotReady", Red, Icons.Filled.Warning, 0)
    val SUSPENDED = StatusToken("Suspended", Purple, Icons.Filled.Lock, 1)
    val UNKNOWN = StatusToken("Unknown", Gray, Icons.Filled.Info, 2)

    private val byName: Map<String, StatusToken> = listOf(
        READY, PROGRESSING, NOT_READY, SUSPENDED,
    ).associateBy { it.label }

    fun resolve(name: String?): StatusToken =
        name?.let { byName[it] } ?: UNKNOWN
}

/** Cluster probe states, mirroring spec 03 [ConnectionStatus]. */
object ProbeStatus {
    val REACHABLE = StatusToken("Reachable", Green, Icons.Filled.CheckCircle, 1)
    val UNREACHABLE = StatusToken("Unreachable", Red, Icons.Filled.Warning, 0)
    val UNVALIDATED = StatusToken("Unknown", Gray, Icons.Filled.Info, 2)

    fun resolve(status: ConnectionStatus): StatusToken = when (status) {
        is ConnectionStatus.Reachable -> REACHABLE
        is ConnectionStatus.Unreachable -> UNREACHABLE
        is ConnectionStatus.Unvalidated -> UNVALIDATED
    }
}

/** Buckets used by the fleet health bar (spec 12 goal 3). */
enum class HealthBucket(val token: StatusToken) {
    FAILED_RED(PodStatus.FAILED),
    PENDING_AMBER(PodStatus.PENDING),
    IN_PROGRESS_BLUE(WorkloadStatus.PROGRESSING),
    OK_GREEN(PodStatus.RUNNING),
    UNKNOWN_GRAY(PodStatus.UNKNOWN),
}
