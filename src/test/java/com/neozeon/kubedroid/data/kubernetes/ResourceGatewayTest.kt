package com.neozeon.kubedroid.data.kubernetes

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 04 stage 2 Testing: the generic gateway against MockWebServer, via
 * the REAL ApiClientProvider fixture (auth headers inherited, exactly like
 * production). Covers URL/query construction, continue token, retry policy,
 * no-retry on 4xx and malformed JSON.
 */
class ResourceGatewayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<MockWebServer>()

    @After fun tearDown() = servers.forEach { runCatching { it.shutdown() } }

    private fun newServer(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private class Fixture(tmp: TemporaryFolder, serverUrl: String) {
        val f = TestClusterFixture(tmp.newFolder())
        val id: java.util.UUID = kotlinx.coroutines.runBlocking { f.add(kubeconfigFor(serverUrl, "token: GW-TOKEN")) }
    }

    private fun gateway(provider: ApiClientProvider, delays: MutableList<Long>) =
        ResourceGateway(provider) { delays += it }

    private fun Fixture(server: MockWebServer) =
        Fixture(tmp, server.url("/").toString().trimEnd('/'))

    @Test fun listPathAndAuthHeader() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("""{"items":[],"metadata":{}}"""))
        val f = Fixture(server)
        val result = gateway(f.f.provider, mutableListOf())
            .list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null)
        assertTrue("expected Ok, got $result", result is ResourceGateway.Result.Ok)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/pods?limit=500", recorded.path)
        assertEquals("Bearer GW-TOKEN", recorded.getHeader("Authorization"))
    }

    @Test fun scopedAndGroupedListPaths() = runBlocking {
        val server = newServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"items":[],"metadata":{}}""")) }
        val f = Fixture(server)
        val gw = gateway(f.f.provider, mutableListOf())
        gw.list(f.id, "ctx1", ResourceCatalog.find("apps/v1", "deployments")!!, "kube-system")
        gw.list(f.id, "ctx1", ResourceCatalog.find("v1", "nodes")!!, null)
        assertEquals("/apis/apps/v1/namespaces/kube-system/deployments?limit=500", server.takeRequest().path)
        assertEquals("/api/v1/nodes?limit=500", server.takeRequest().path)
    }

    @Test fun labelSelectorQueryParam() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("""{"items":[],"metadata":{}}"""))
        val f = Fixture(server)
        gateway(f.f.provider, mutableListOf())
            .list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null,
                labelSelector = "app=web,tier=front")
        assertEquals(
            "/api/v1/pods?limit=500&labelSelector=app%3Dweb%2Ctier%3Dfront",
            server.takeRequest().path,
        )
    }

    @Test fun continueTokenUrlEncoded() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("""{"items":[],"metadata":{"continue":"abc/123 x"}}"""))
        val f = Fixture(server)
        gateway(f.f.provider, mutableListOf())
            .list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null, continueToken = "abc/123 x")
        assertEquals("/api/v1/pods?limit=500&continue=abc%2F123%20x", server.takeRequest().path)
    }

    @Test fun objectGetPath() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("""{"metadata":{"name":"node-a"}}"""))
        val f = Fixture(server)
        gateway(f.f.provider, mutableListOf())
            .get(f.id, "ctx1", ResourceCatalog.find("v1", "nodes")!!, null, "node-a")
        assertEquals("/api/v1/nodes/node-a", server.takeRequest().path)
    }

    @Test fun retriesOn5xxThenSucceeds() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"items":[],"metadata":{}}"""))
        val f = Fixture(server)
        val delays = mutableListOf<Long>()
        val result = gateway(f.f.provider, delays).list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null)
        assertTrue("expected Ok after retries, got $result", result is ResourceGateway.Result.Ok)
        assertEquals(listOf(500L, 2000L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test fun noRetryOn403() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"forbidden"}"""))
        val f = Fixture(server)
        val delays = mutableListOf<Long>()
        val result = gateway(f.f.provider, delays)
            .list(f.id, "ctx1", ResourceCatalog.find("v1", "secrets")!!, "ns")
        assertEquals(ResourceGateway.Result.Http(403, """{"message":"forbidden"}"""), result)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), delays)
    }

    @Test fun networkFailuresRetryThenGiveUp() = runBlocking {
        val server = newServer()
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        val f = Fixture(server)
        val delays = mutableListOf<Long>()
        val result = gateway(f.f.provider, delays).list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null)
        assertTrue("expected Network, got $result", result is ResourceGateway.Result.Network)
        assertEquals(listOf(500L, 2000L), delays)
        assertEquals(3, server.requestCount) // initial + 2 retries
    }

    @Test fun malformedJsonReported() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("not json at all"))
        val f = Fixture(server)
        val result = gateway(f.f.provider, mutableListOf())
            .list(f.id, "ctx1", ResourceCatalog.find("v1", "pods")!!, null)
        assertTrue("expected Malformed, got $result", result is ResourceGateway.Result.Malformed)
    }
}
