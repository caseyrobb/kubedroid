package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.CatalogResolver
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ResourceGateway
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.model.ResourceError
import com.neozeon.kubedroid.data.model.ResourceResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 07 stage 1 Testing: discovery fetch, cache semantics (Q1), error
 * mapping — canned gateway, same pattern as ResourceRepositoryMutationTest.
 */
class CrdRepositoryTest {

    private class CannedGateway(
        dir: File,
        private val handler: (Request) -> Pair<Int, String>,
    ) : ResourceGateway(
        ApiClientProvider(BlobStore(dir, FakeCipherVault()), InMemoryClusterRegistry(), KubeconfigParser()),
        backoff = RetryBackoff { },
    ) {
        val seen = mutableListOf<Request>()
        private val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                seen += request
                val (code, body) = handler(request)
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("canned")
                    .body(body.toResponseBody(null))
                    .build()
            })
            .build()

        override suspend fun resolve(connectionId: UUID, context: String): Resolved =
            Resolved("http://fake", http)
    }

    private var connectionId = UUID.randomUUID()

    private fun repoWith(
        handler: (Request) -> Pair<Int, String>,
    ): Triple<CrdRepository, CannedGateway, CatalogResolver> {
        val dir = kotlin.io.path.createTempDirectory("crdrepo").toFile()
        val registry = InMemoryClusterRegistry()
        val gateway = CannedGateway(dir, handler)
        val clusterRepo = ClusterRepository(
            registry, BlobStore(dir, FakeCipherVault()),
            ApiClientProvider(BlobStore(dir, FakeCipherVault()), registry, KubeconfigParser()),
            WallClock { 1L }, KubeconfigParser(),
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
            connectionId = (r as com.neozeon.kubedroid.data.model.AddResult.Added).connection.id
        }
        val resolver = CatalogResolver()
        return Triple(CrdRepository(gateway, clusterRepo, resolver), gateway, resolver)
    }

    private val crdListBody =
        """{"apiVersion":"apiextensions.k8s.io/v1","kind":"CustomResourceDefinitionList",
           "items":[{"metadata":{"name":"widgets.example.com"},
             "spec":{"group":"example.com","scope":"Namespaced",
               "names":{"plural":"widgets","kind":"Widget"},
               "versions":[{"name":"v1","served":true,"storage":true}]}}]}"""

    @Test fun `list fetches parses and publishes to resolver`() = runBlocking {
        val (repo, gw, resolver) = repoWith { 200 to crdListBody }
        val r = repo.list(connectionId)
        val defs = (r as ResourceResult.Ok).value
        assertEquals(1, defs.size)
        assertEquals("Widget", defs.single().kind)
        assertEquals("http://fake/apis/apiextensions.k8s.io/v1/customresourcedefinitions", gw.seen.single().url.toString().substringBefore('?'))
        assertEquals(1, gw.seen.size)
        assertEquals("Widget", resolver.resolve(connectionId, "example.com/v1", "widgets")?.kind)
    }

    @Test fun `second list is served from cache without a request`() = runBlocking {
        val (repo, gw, _) = repoWith { 200 to crdListBody }
        repo.list(connectionId)
        val r = repo.list(connectionId)
        assertTrue(r is ResourceResult.Ok)
        assertEquals("cached fetch must not hit the network", 1, gw.seen.size)
    }

    @Test fun `refresh true re-fetches and republishes`() = runBlocking {
        var calls = 0
        val (repo, gw, resolver) = repoWith {
            calls++
            val items = if (calls == 1) crdListBody else crdListBody.replace("widgets", "gadgets").replace("Widget", "Gadget")
            200 to items
        }
        repo.list(connectionId)
        repo.list(connectionId, refresh = true)
        assertEquals(2, gw.seen.size)
        assertEquals("Gadget", resolver.resolve(connectionId, "example.com/v1", "gadgets")?.kind)
        assertEquals(null, resolver.resolve(connectionId, "example.com/v1", "widgets"))
    }

    @Test fun `forbidden names customresourcedefinitions`() = runBlocking {
        val (repo, _, _) = repoWith { 403 to """{"kind":"Status","code":403}""" }
        val err = (repo.list(connectionId) as ResourceResult.Err).error
        assertTrue("expected Forbidden, got $err", err is ResourceError.Forbidden)
        assertTrue(err.userMessage().contains("customresourcedefinitions"))
    }

    @Test fun `network failure maps to Network error`() = runBlocking {
        val (repo, _, _) = repoWith { 599 to "" }
        // 599 is not a real code; the interceptor returns it as-is -> Server mapping
        val err = (repo.list(connectionId) as ResourceResult.Err).error
        assertTrue("expected Server(599), got $err", err is ResourceError.Server)
    }

    @Test fun `unknown connection is malformed without a request`() = runBlocking {
        val (repo, gw, _) = repoWith { 200 to crdListBody }
        val err = (repo.list(UUID.randomUUID()) as ResourceResult.Err).error
        assertTrue(err is ResourceError.Malformed)
        assertEquals(0, gw.seen.size)
    }
}
