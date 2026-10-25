package com.neozeon.kubedroid.data.model

import java.util.UUID

/**
 * Persisted metadata for one imported cluster connection.
 * Never carries secrets — the kubeconfig payload lives encrypted on disk
 * (spec 03 "Secure storage"); this record is the DataStore row.
 */
data class ClusterConnection(
    val id: UUID,
    val displayName: String,
    val addedAtEpochSec: Long,
    /** Hex SHA-256 of the raw kubeconfig bytes; duplicate-import detection only. */
    val payloadSha256: String,
    /** Currently selected context for this row (stage 2 refinement). */
    val contextName: String,
    /** Non-secret context-name list so the dropdown renders without decrypting. */
    val contexts: List<String>,
    /** Cluster uses insecure-skip-tls-verify ⇒ persistent warning badge (spec 03 UI). */
    val insecureTls: Boolean = false,
)

/** Live connectivity state, kept in memory only (spec 03 "Connection probe"). */
sealed interface ConnectionStatus {
    data object Unvalidated : ConnectionStatus
    data class Reachable(val serverVersion: String) : ConnectionStatus
    data class Unreachable(val reason: String) : ConnectionStatus
}

/** The globally selected (connection, context); nulls mean "nothing selected". */
data class ActiveSelection(
    val connectionId: UUID?,
    val contextName: String?,
)

/** Result of [com.neozeon.kubedroid.data.local.ClusterRepository.add]. */
sealed interface AddResult {
    data class Added(val connection: ClusterConnection) : AddResult
    /** Raw payload byte-identical to an existing connection; nothing persisted. */
    data class Duplicate(val existingId: UUID) : AddResult
    /** Payload failed KubeconfigParser validation; nothing persisted. */
    data class Rejected(val reason: String) : AddResult
}
