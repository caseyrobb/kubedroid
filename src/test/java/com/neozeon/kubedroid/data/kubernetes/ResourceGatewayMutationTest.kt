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
 * Spec 06 stage 1 Testing: the mutation half of ResourceGateway against
 * MockWebServer via the REAL ApiClientProvider fixture (auth inherited).
 * The defining policy: exactly ONE request per mutation, always — no retry
 * loop even on 5xx or IO failure.
 */
class ResourceGatewayMutationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<MockWebServer>()

    @After fun tearDown() = servers.forEach { runCatching { it.shutdown() } }

    private fun newServer(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private inner class Fixture(server: MockWebServer) {
        val f = TestClusterFixture(tmp.newFolder())
        val id: java.util.UUID = runBlocking {
            f.add(kubeconfigFor(server.url("/").toString().trimEnd('/'), "token: GW-TOKEN"))
        }
        val gateway = ResourceGateway(f.provider) { }
    }

    private val pods = { ResourceCatalog.find("v1", "pods")!! }

    @Test fun putSendsBodyAuthAndItemPath() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("""{"metadata":{"name":"web-1"}}"""))
        val fx = Fixture(server)
        val result = fx.gateway.replace(fx.id, "ctx1", pods(), "web", "web-1", """{"spec":{"replicas":2}}""")
        assertTrue("expected Ok, got $result", result is ResourceGateway.Result.Ok)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/namespaces/web/pods/web-1", recorded.path)
        assertEquals("Bearer GW-TOKEN", recorded.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("""{"spec":{"replicas":2}}""", recorded.body.readUtf8())
    }

    @Test fun deleteUsesItemPathAndTreatsEmptyBodyAsOk() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val fx = Fixture(server)
        val result = fx.gateway.delete(fx.id, "ctx1", pods(), "web", "web-1")
        assertTrue("expected Ok, got $result", result is ResourceGateway.Result.Ok)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/namespaces/web/pods/web-1", recorded.path)
    }

    @Test fun clusterScopedItemPathHasNoNamespaceSegment() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val fx = Fixture(server)
        fx.gateway.delete(fx.id, "ctx1", ResourceCatalog.find("v1", "nodes")!!, null, "node-a")
        assertEquals("/api/v1/nodes/node-a", server.takeRequest().path)
    }

    @Test fun itemPathPercentEncodesSpecialSegments() {
        val path = ResourceCatalog.itemPath(pods(), "a b/c", "n@me 1")
        assertEquals("/api/v1/namespaces/a%20b%2Fc/pods/n%40me%201", path)
    }

    @Test fun serverErrorIsNotRetried() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val fx = Fixture(server)
        val result = fx.gateway.replace(fx.id, "ctx1", pods(), "web", "web-1", "{}")
        assertTrue("expected Http(500), got $result", result is ResourceGateway.Result.Http && (result as ResourceGateway.Result.Http).code == 500)
        assertEquals("mutations must issue exactly one request", 1, server.requestCount)
    }

    @Test fun networkFailureIsNotRetried() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val fx = Fixture(server)
        val result = fx.gateway.delete(fx.id, "ctx1", pods(), "web", "web-1")
        assertTrue("expected Network, got $result", result is ResourceGateway.Result.Network)
        assertEquals("mutations must issue exactly one request", 1, server.requestCount)
    }

    @Test fun conflictCodePassesThroughWithBody() = runBlocking {
        val server = newServer()
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"kind":"Status","message":"Storage object was modified","code":409}"""),
        )
        val fx = Fixture(server)
        val result = fx.gateway.replace(fx.id, "ctx1", pods(), "web", "web-1", "{}")
        assertTrue("expected Http(409), got $result", result is ResourceGateway.Result.Http && (result as ResourceGateway.Result.Http).code == 409)
        assertTrue(
            "body must be retained for Status.message extraction",
            (result as ResourceGateway.Result.Http).bodySnippet.contains("Storage object was modified"),
        )
    }
}
