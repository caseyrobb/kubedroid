package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.model.RedactedValue
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceRef
import com.neozeon.kubedroid.data.model.ResourceResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 04 stage 3 Testing: repository-level error mapping, page/summary
 * mapping, Secret redaction, namespace list — via a gateway subclass whose
 * OkHttp layer is fully canned (no server needed).
 */
class ResourceRepositoryTest {

    private class CannedGateway(dir: File, handler: (path: String) -> Pair<Int, String>) :
        ResourceGateway(
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), InMemoryClusterRegistry(), KubeconfigParser()),
            backoff = RetryBackoff { },
        ) {
        val seenPaths = mutableListOf<String>()
        private val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                seenPaths += request.url.encodedPath + "?" + request.url.encodedQuery
                val (code, body) = handler(request.url.encodedPath)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(body.toResponseBody(null))
                    .build()
            })
            .build()

        override suspend fun resolve(connectionId: UUID, context: String): Resolved =
            Resolved("http://fake", http)
    }

    private var connectionId = UUID.randomUUID()

    private fun repoWith(handler: (path: String) -> Pair<Int, String>): Pair<ResourceRepository, CannedGateway> {
        val dir = kotlin.io.path.createTempDirectory("resrepo").toFile()
        val registry = InMemoryClusterRegistry()
        val gateway = CannedGateway(dir, handler)
        val clusterRepo = ClusterRepository(
            registry, BlobStore(dir, FakeCipherVault()),
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), registry, KubeconfigParser()),
            WallClock { 1L },
            KubeconfigParser(),
        )
        runBlocking {
            val r = clusterRepo.add(
                """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters: [{name: c1, cluster: {server: "https://x:6443"}}]
                users: [{name: u1, user: {token: t}}]
                contexts: [{name: ctx1, context: {cluster: c1, user: u1}}]
                """.trimIndent(),
                "T", "ctx1",
            )
            // the registry assigns the id; tests must target the REAL one
            connectionId = (r as com.neozeon.kubedroid.data.model.AddResult.Added).connection.id
        }
        return ResourceRepository(gateway, clusterRepo) to gateway
    }

    private val podsJson = """
        {"items":[
          {"metadata":{"name":"web-1","namespace":"web","labels":{"app":"web"},
            "creationTimestamp":"2026-10-01T00:00:00Z"},
           "spec":{"nodeName":"w1","containers":[{},{}]},
           "status":{"phase":"Running","containerStatuses":[{"ready":true},{"ready":true}]}},
          {"metadata":{"name":"job-9","namespace":"batch"},
           "spec":{"containers":[{}]},"status":{"phase":"Pending"}}
        ],"metadata":{"continue":"TOK/1"}}
    """.trimIndent()

    @Test fun listMapsSummariesAndContinueToken() = runBlocking {
        val (repo, gw) = repoWith { 200 to podsJson }
        val r = repo.list(connectionId, "v1", "pods", null)
        assertTrue(r is ResourceResult.Ok)
        val page = (r as ResourceResult.Ok).value
        assertEquals(2, page.items.size)
        assertEquals("TOK/1", page.continueToken)
        val web = page.items[0]
        assertEquals("web-1", web.name)
        assertEquals("web", web.namespace)
        assertEquals(mapOf("app" to "web"), web.labels)
        assertEquals("Running", web.primary)
        assertEquals("2/2 ready · w1", web.secondary)
        assertEquals("2026-10-01T00:00:00Z", web.createdAt)
        assertEquals(0, page.items[1].labels.size)
        assertEquals(true, gw.seenPaths.single().contains("limit=500"))
    }

    @Test fun forbiddenMapsWithNamespaceScope() = runBlocking {
        val (repo, _) = repoWith { if (it.contains("secrets")) 403 to "{}" else 200 to """{"items":[],"metadata":{}}""" }
        val r = repo.list(connectionId, "v1", "secrets", "kube-system")
        assertTrue("expected Forbidden, got $r", r is ResourceResult.Err)
        val err = (r as ResourceResult.Err).error
        assertTrue(err is ResourceError.Forbidden)
        assertTrue(err.userMessage().contains("namespace kube-system"))
    }

    @Test fun unknownTypeIsMalformed() = runBlocking {
        val (repo, _) = repoWith { 200 to """{"items":[]}""" }
        val r = repo.list(connectionId, "v1", "frobnicators", null)
        assertTrue(r is ResourceResult.Err && r.error is ResourceError.Malformed)
    }

    @Test fun unknownConnectionFails() = runBlocking {
        val (repo, _) = repoWith { 200 to """{"items":[]}""" }
        val r = repo.list(UUID.randomUUID(), "v1", "pods", null)
        assertTrue(r is ResourceResult.Err)
    }

    @Test fun secretDetailIsRedactedThenRevealed() = runBlocking {
        val secretJson = """
            {"metadata":{"name":"db","namespace":"web"},
             "type":"Opaque",
             "data":{"password":"Y29udHJvbA==","cert":"AQID"}}
        """.trimIndent()
        val (repo, _) = repoWith { 200 to secretJson }
        val ref = ResourceRef(connectionId, "v1", "secrets", "web", "db")

        val hidden = repo.detail(ref)
        assertTrue(hidden is ResourceResult.Ok)
        val obj = (hidden as ResourceResult.Ok).value.obj
        val data = obj["data"] as Map<*, *>
        assertEquals(RedactedValue(7), data["password"]) // "control" = 7 bytes
        assertEquals(RedactedValue(3), data["cert"]) // bytes 1,2,3
        val rendered = obj.toString()
        assertFalse("base64 leaked: $rendered", rendered.contains("Y29udHJvbA=="))
        assertFalse("decoded leaked: $rendered", rendered.contains("control"))

        val revealed = repo.detail(ref, reveal = true)
        val revealedData = ((revealed as ResourceResult.Ok).value.obj["data"]) as Map<*, *>
        assertEquals("Y29udHJvbA==", revealedData["password"])
    }

    @Test fun nonSecretDetailNotRedacted() = runBlocking {
        val (repo, _) = repoWith { 200 to """{"metadata":{"name":"c"},"data":{"k":"v"}}""" }
        val ref = ResourceRef(connectionId, "v1", "configmaps", "web", "c")
        val r = repo.detail(ref)
        assertEquals(mapOf("k" to "v"), ((r as ResourceResult.Ok).value.obj["data"]))
    }

    @Test fun namespacesReturnsNames() = runBlocking {
        val (repo, _) = repoWith {
            200 to """{"items":[{"metadata":{"name":"default"}},{"metadata":{"name":"kube-system"}}]}"""
        }
        val r = repo.namespaces(connectionId)
        assertEquals(listOf("default", "kube-system"), (r as ResourceResult.Ok).value)
    }
}
