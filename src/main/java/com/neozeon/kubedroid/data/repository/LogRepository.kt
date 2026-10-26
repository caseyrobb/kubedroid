package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.LogGateway
import com.neozeon.kubedroid.data.kubernetes.LogLineEvent
import com.neozeon.kubedroid.data.kubernetes.LogRequest
import com.neozeon.kubedroid.data.kubernetes.SaveRequest
import com.neozeon.kubedroid.data.kubernetes.StreamEndReason
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.model.ResourceError
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Spec 05 "LogRepository": context resolution (same rule as spec 04's
 * ResourceRepository — a connection that vanished mid-flight maps to
 * Malformed), local container validation (friendlier than the server 400,
 * listing the known names), and pass-through of the gateway's single
 * error-mapping table (pods/log 403 copy lives in LogGateway.mapHttp).
 *
 * No caching, no persistence: logs are memory-only, and the container list
 * comes from the already-loaded detail object, passed in by the caller.
 */
@Singleton
open class LogRepository @Inject constructor(
    private val gateway: LogGateway,
    private val clusterRepository: ClusterRepository,
) {
    private fun contextFor(connectionId: UUID): String? =
        clusterRepository.connections.value.firstOrNull { it.id == connectionId }?.contextName

    private fun connectionGone(connectionId: UUID) =
        ResourceError.Malformed("connection $connectionId is no longer available")

    open fun stream(
        connectionId: UUID,
        namespace: String,
        podName: String,
        container: String?,
        previous: Boolean,
        follow: Boolean,
    ): Flow<LogLineEvent> {
        val context = contextFor(connectionId)
            ?: return flowOf(
                LogLineEvent.StreamEnd(StreamEndReason.Error(connectionGone(connectionId))),
            )
        return gateway.stream(
            LogRequest(connectionId, context, namespace, podName, container, previous, follow),
        )
    }

    open suspend fun save(
        connectionId: UUID,
        namespace: String,
        podName: String,
        container: String?,
        previous: Boolean,
        sink: OutputStream,
    ): ResourceError? {
        val context = contextFor(connectionId) ?: return connectionGone(connectionId)
        return gateway.save(
            SaveRequest(connectionId, context, namespace, podName, container, previous),
            sink,
        )
    }

    /** Local pre-flight check (spec 05): null container = auto-select = ok. */
    fun validateContainer(
        requested: String?,
        regular: List<String>,
        init: List<String>,
    ): ResourceError? {
        if (requested == null) return null
        val known = regular + init
        return if (requested in known) null else ResourceError.ContainerNotFound(requested, known)
    }
}
