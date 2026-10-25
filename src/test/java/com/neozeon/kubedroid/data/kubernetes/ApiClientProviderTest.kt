package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.model.AddResult
import com.neozeon.kubedroid.data.model.ConnectionStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestClusterFixture(val dir: File) {
    val registry = InMemoryClusterRegistry()
    val blobStore = BlobStore(dir, FakeCipherVault())
    val parser = KubeconfigParser()
    val provider = ApiClientProvider(blobStore, registry, parser)
    val repository = ClusterRepository(
        registry, blobStore, provider, WallClock { 1L }, parser,
    )

    suspend fun add(raw: String): UUID =
        when (val r = repository.add(raw, "test", "ctx1")) {
            is AddResult.Added -> r.connection.id
            is AddResult.Duplicate -> error("unexpected duplicate")
            is AddResult.Rejected -> error("rejected: ${r.reason}")
        }
}

fun kubeconfigFor(server: String, authBlock: String = "token: TESTTOKEN", context: String = "ctx1") = """
    apiVersion: v1
    kind: Config
    current-context: $context
    clusters:
    - name: c1
      cluster:
        server: $server
    contexts:
    - name: ctx1
      context: { cluster: c1, user: u1 }
    - name: ctx2
      context: { cluster: c1 }
    users:
    - name: u1
      user:
        $authBlock
""".trimIndent()

/**
 * Spec 03 Testing: ApiClientProvider cache semantics and auth headers,
 * plus probe status mapping, against MockWebServer.
 */
class ApiClientProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<MockWebServer>()

    @After fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun newServer(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    @Test fun sameKeyReturnsCachedInstance() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        val id = f.add(kubeconfigFor(server.url("/").toString().trimEnd('/')))
        val a = f.provider.clientFor(id, "ctx1")
        val b = f.provider.clientFor(id, "ctx1")
        assertSame("cache must return the same ApiClient", a, b)
    }

    @Test fun differentContextGivesDistinctClient() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        val id = f.add(kubeconfigFor(server.url("/").toString().trimEnd('/')))
        val c1 = f.provider.clientFor(id, "ctx1")
        val c2 = f.provider.clientFor(id, "ctx2")
        assertNotSame("distinct (id, context) keys must not share instances", c1, c2)
    }

    @Test fun lruEvictsOldestBeyondFour() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        val url = server.url("/").toString().trimEnd('/')
        val ids = (1..5).map { f.add(kubeconfigFor(url, "token: TOKEN-$it")) }
        val first = f.provider.clientFor(ids[0], "ctx1")
        ids.drop(1).forEach { f.provider.clientFor(it, "ctx1") }
        // first was eldest; after 4 more inserts it must have been evicted
        val refetched = f.provider.clientFor(ids[0], "ctx1")
        assertNotSame("LRU must evict beyond 4 entries", first, refetched)
    }

    @Test fun timeoutsPinnedPerSpec() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        val id = f.add(kubeconfigFor(server.url("/").toString().trimEnd('/')))
        val okHttp = f.provider.clientFor(id, "ctx1").httpClient
        assertEquals(15_000, okHttp.connectTimeoutMillis)
        assertEquals(30_000, okHttp.readTimeoutMillis)
    }

    @Test fun bearerTokenHeaderSent() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setBody("{}"))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url, "token: BEARER-VAL"))
        val client = f.provider.clientFor(id, "ctx1")
        client.httpClient.newCall(
            okhttp3.Request.Builder().url(url + "/version").build(),
        ).execute().close()
        val recorded = server.takeRequest()
        assertEquals("Bearer BEARER-VAL", recorded.getHeader("Authorization"))
    }

    @Test fun basicAuthHeaderSent() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setBody("{}"))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url, "username: admin\n        password: pw123"))
        val client = f.provider.clientFor(id, "ctx1")
        client.httpClient.newCall(
            okhttp3.Request.Builder().url(url + "/version").build(),
        ).execute().close()
        val recorded = server.takeRequest()
        assertEquals(okhttp3.Credentials.basic("admin", "pw123"), recorded.getHeader("Authorization"))
    }

    @Test fun evictRemovesAllContextsOfConnection() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url))
        val a = f.provider.clientFor(id, "ctx1")
        f.provider.evict(id)
        assertNotSame(a, f.provider.clientFor(id, "ctx1"))
    }

    // ---- probe mapping -------------------------------------------------

    @Test fun probeGreenAgainstVersionEndpoint() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"gitVersion":"v1.36.4"}"""))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url))
        val status = f.repository.probe(id)
        assertTrue("expected Reachable, got $status", status is ConnectionStatus.Reachable)
        assertTrue(
            (status as ConnectionStatus.Reachable).serverVersion.contains("v1.36.4"),
        )
        assertEquals("/version", server.takeRequest().path)
    }

    @Test fun probe401Mapped() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(401))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url))
        val status = f.repository.probe(id)
        assertTrue(status is ConnectionStatus.Unreachable)
        assertTrue((status as ConnectionStatus.Unreachable).reason.contains("401"))
    }

    @Test fun probe403Mapped() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(403))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url))
        val status = f.repository.probe(id)
        assertTrue((status as ConnectionStatus.Unreachable).reason.contains("403"))
    }

    @Test fun probeDnsFailureMapped() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val id = f.add(kubeconfigFor("https://kubedroid-does-not-exist.invalid"))
        val status = f.repository.probe(id)
        assertTrue(status is ConnectionStatus.Unreachable)
        assertTrue((status as ConnectionStatus.Unreachable).reason.contains("host not found"))
    }

    @Test fun probeRecordsStatusFlow() = runTest {
        val f = TestClusterFixture(tmp.newFolder())
        val server = newServer()
        server.enqueue(MockResponse().setBody("{}"))
        val url = server.url("/").toString().trimEnd('/')
        val id = f.add(kubeconfigFor(url))
        f.repository.probe(id)
        assertTrue(f.repository.statuses.value[id] is ConnectionStatus.Reachable)
    }
}
