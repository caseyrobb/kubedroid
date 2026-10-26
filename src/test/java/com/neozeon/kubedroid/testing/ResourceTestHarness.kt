package com.neozeon.kubedroid.testing

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.model.AddResult
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.repository.ResourceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Shared spec 04 test rig: a ResourceGateway whose HTTP is fully canned
 * (path/query -> code+body), a ResourceRepository over it, and a registered
 * connection. Screen and ViewModel tests build ViewModels on top.
 */
class ResourceTestHarness(
    private val handler: (path: String, query: String?) -> Pair<Int, String>,
    /** Spec 06: request-aware override (method/body), used when non-null. */
    private val requestHandler: ((okhttp3.Request) -> Pair<Int, String>)? = null,
) {
    val dir: File = kotlin.io.path.createTempDirectory("restest").toFile()
    val seen = mutableListOf<String>()
    val seenRequests = mutableListOf<okhttp3.Request>()

    private val http = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            synchronized(seen) {
                seen += request.url.encodedPath + "?" + request.url.encodedQuery
                seenRequests += request
            }
            val (code, body) = requestHandler?.invoke(request) ?: handler(request.url.encodedPath, request.url.encodedQuery)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(body.toResponseBody(null)).build()
        })
        .build()

    private val gateway = object : ResourceGateway(
        ApiClientProvider(BlobStore(dir, FakeCipherVault()), InMemoryClusterRegistry(), KubeconfigParser()),
        backoff = RetryBackoff { },
    ) {
        override suspend fun resolve(connectionId: UUID, context: String): Resolved =
            Resolved("http://fake", http)
    }

    val repository: ResourceRepository

    /** Spec 07: discovery repository over the same canned gateway. */
    val crdRepository: com.neozeon.kubedroid.data.repository.CrdRepository

    /** The id the registry actually assigned — tests must target THIS id. */
    val connectionId: UUID

    init {
        val registry = InMemoryClusterRegistry()
        val clusterRepo = ClusterRepository(
            registry, BlobStore(dir, FakeCipherVault()),
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), registry, KubeconfigParser()),
            WallClock { 1L }, KubeconfigParser(),
        )
        connectionId = runBlocking {
            when (val r = clusterRepo.add(
                """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters: [{name: c1, cluster: {server: "https://x:6443"}}]
                users: [{name: u1, user: {token: t}}]
                contexts: [{name: ctx1, context: {cluster: c1, user: u1}}]
                """.trimIndent(),
                "Test cluster", "ctx1",
            )) {
                is AddResult.Added -> r.connection.id
                else -> error("fixture cluster rejected: $r")
            }
        }
        repository = ResourceRepository(gateway, clusterRepo)
        crdRepository = com.neozeon.kubedroid.data.repository.CrdRepository(
            gateway, clusterRepo, com.neozeon.kubedroid.data.kubernetes.CatalogResolver(),
        )
    }

    fun lastSeen(): String = synchronized(seen) { seen.last() }
}

/** Common canned-JSON builders for spec 04 tests. */
object ResourceFixtures {
    fun pod(name: String, ns: String = "web", app: String? = null, phase: String = "Running", rv: String? = null) =
        """{"apiVersion":"v1","kind":"Pod","metadata":{"name":"$name","namespace":"$ns",""" +
            (rv?.let { """"resourceVersion":"$it",""" } ?: "") +
            (app?.let { """"labels":{"app":"$it"},""" } ?: "") +
            """"creationTimestamp":"2026-01-01T00:00:00Z"},"spec":{"containers":[{"name":"c","image":"nginx"}]},"status":{"phase":"$phase","podIP":"10.42.0.7"}}"""

    fun itemsJson(items: List<String>, cont: String? = null) =
        """{"items":[${items.joinToString(",")}],"metadata":{""" +
            (cont?.let { """"continue":"$it"""" } ?: "") + """}}"""

    fun secret(ns: String = "web", name: String = "db") =
        """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"$name","namespace":"$ns","creationTimestamp":"2026-01-01T00:00:00Z"},"type":"Opaque","data":{"pw":"eA=="}}"""
}
