package com.neozeon.kubedroid.data.local

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ParseResult
import com.neozeon.kubedroid.data.model.ActiveSelection
import com.neozeon.kubedroid.data.model.AddResult
import com.neozeon.kubedroid.data.model.ClusterConnection
import com.neozeon.kubedroid.data.model.ConnectionStatus
import com.neozeon.kubedroid.data.registry.ClusterRegistry
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request

/** Test seam for wall time. */
fun interface WallClock {
    fun epochSec(): Long
}

/**
 * Owns the full connection lifecycle (spec 03): encrypted add with
 * SHA-256 dedup, rename, delete-with-wipe (registry row removed BEFORE
 * the blob, cached clients evicted), active selection, and the
 * GET <server>/version probe.
 */
@Singleton
class ClusterRepository @Inject constructor(
    private val registry: ClusterRegistry,
    private val blobStore: BlobStore,
    private val apiClientProvider: ApiClientProvider,
    private val clock: WallClock,
    private val parser: KubeconfigParser,
) {
    val connections: StateFlow<List<ClusterConnection>> = registry.connections
    val activeSelection: StateFlow<ActiveSelection> = registry.activeSelection

    private val _statuses = MutableStateFlow<Map<UUID, ConnectionStatus>>(emptyMap())
    val statuses: StateFlow<Map<UUID, ConnectionStatus>> = _statuses.asStateFlow()

    suspend fun add(raw: String, displayName: String, contextName: String): AddResult {
        // Repository is the security boundary: re-parse, never trust the UI.
        val parsed = when (val r = parser.parse(raw)) {
            is ParseResult.Valid -> r.config
            is ParseResult.Invalid -> return AddResult.Rejected(r.error.userMessage())
        }
        val selected = parsed.contexts.firstOrNull { it.name == contextName }
            ?: return AddResult.Rejected("unknown context '$contextName'")
        val cluster = parsed.clusters.first { it.name == selected.clusterName }
        val sha = PayloadHash.sha256Hex(raw.toByteArray(Charsets.UTF_8))
        registry.connections.value.firstOrNull { it.payloadSha256 == sha }?.let {
            return AddResult.Duplicate(it.id)
        }
        val connection = ClusterConnection(
            id = UUID.randomUUID(),
            displayName = displayName,
            addedAtEpochSec = clock.epochSec(),
            payloadSha256 = sha,
            contextName = contextName,
            contexts = parsed.contexts.map { it.name },
            insecureTls = cluster.insecureSkipTlsVerify,
        )
        blobStore.write(connection.id, raw.toByteArray(Charsets.UTF_8))
        registry.setConnections(registry.connections.value + connection)
        registry.setActive(ActiveSelection(connection.id, contextName))
        _statuses.value = _statuses.value + (connection.id to ConnectionStatus.Unvalidated)
        return AddResult.Added(connection)
    }

    suspend fun rename(id: UUID, displayName: String) {
        mutate(id) { it.copy(displayName = displayName) }
    }

    /** Spec order: DataStore entry first, then blob, then cache eviction. */
    suspend fun delete(id: UUID) {
        registry.setConnections(registry.connections.value.filterNot { it.id == id })
        blobStore.delete(id)
        apiClientProvider.evict(id)
        _statuses.value = _statuses.value - id
        if (activeSelection.value.connectionId == id) {
            registry.setActive(ActiveSelection(null, null))
        }
    }

    suspend fun setActive(id: UUID?) {
        val conn = id?.let { target -> registry.connections.value.firstOrNull { it.id == target } }
        registry.setActive(
            if (conn == null) ActiveSelection(null, null)
            else ActiveSelection(conn.id, conn.contextName),
        )
    }

    suspend fun selectContext(id: UUID, contextName: String) {
        require(contextName in (registry.connections.value.first { it.id == id }).contexts) {
            "unknown context '$contextName'"
        }
        mutate(id) { it.copy(contextName = contextName) }
        if (activeSelection.value.connectionId == id) {
            registry.setActive(ActiveSelection(id, contextName))
        }
    }

    /** GET <server>/version (spec 03 "Connection probe"); maps failures to friendly text. */
    suspend fun probe(id: UUID): ConnectionStatus {
        val conn = registry.connections.value.firstOrNull { it.id == id }
            ?: return ConnectionStatus.Unreachable("connection no longer exists")
        val status = try {
            val client = apiClientProvider.clientFor(id, conn.contextName)
            val request = Request.Builder().url(client.basePath.trimEnd('/') + "/version").build()
            client.httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ConnectionStatus.Reachable(
                        response.body.string().take(200).ifBlank { "ok" },
                    )
                    response.code == 401 ->
                        ConnectionStatus.Unreachable("API server rejected credentials (401)")
                    response.code == 403 ->
                        ConnectionStatus.Unreachable("forbidden by API server (403)")
                    else -> ConnectionStatus.Unreachable("HTTP ${response.code} from API server")
                }
            }
        } catch (e: UnknownHostException) {
            ConnectionStatus.Unreachable("host not found: ${e.message}")
        } catch (e: SocketTimeoutException) {
            ConnectionStatus.Unreachable("API server timed out")
        } catch (e: SSLException) {
            ConnectionStatus.Unreachable(
                "TLS handshake failed (check CA data or use insecure-skip-tls-verify): ${e.message}",
            )
        } catch (e: IOException) {
            ConnectionStatus.Unreachable(e.message ?: "network error")
        }
        _statuses.value = _statuses.value + (id to status)
        return status
    }

    fun statusOf(id: UUID): ConnectionStatus =
        _statuses.value[id] ?: ConnectionStatus.Unvalidated

    private suspend fun mutate(id: UUID, transform: (ClusterConnection) -> ClusterConnection) {
        registry.setConnections(
            registry.connections.value.map { if (it.id == id) transform(it) else it },
        )
    }
}
