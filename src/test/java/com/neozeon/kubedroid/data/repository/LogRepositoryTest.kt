package com.neozeon.kubedroid.data.repository

import com.neozeon.kubedroid.data.kubernetes.ApiClientProvider
import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.LogGateway
import com.neozeon.kubedroid.data.kubernetes.LogLineEvent
import com.neozeon.kubedroid.data.kubernetes.RetryBackoff
import com.neozeon.kubedroid.data.kubernetes.StreamEndReason
import com.neozeon.kubedroid.data.kubernetes.TestClusterFixture
import com.neozeon.kubedroid.data.kubernetes.kubeconfigFor
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.model.ResourceError
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 05 stage 3 Testing: repository context resolution, delegation to the
 * gateway with the right query, local container validation, save mapping —
 * via a gateway subclass whose OkHttp layer is fully canned (no server).
 */
class LogRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class CannedLogGateway(
        dir: File,
        private val handler: (path: String, query: String) -> Pair<Int, String>,
    ) : LogGateway(
        ApiClientProvider(BlobStore(dir, FakeCipherVault()), InMemoryClusterRegistry(), KubeconfigParser()),
        backoff = RetryBackoff { },
    ) {
        val seen = mutableListOf<Pair<String, String>>()
        private val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                seen += request.url.encodedPath to request.url.encodedQuery.orEmpty()
                val (code, body) = handler(request.url.encodedPath, request.url.encodedQuery.orEmpty())
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("canned").body(body.toResponseBody(null))
                    .build()
            })
            .build()

        override suspend fun resolve(connectionId: UUID, context: String): Resolved =
            Resolved("http://canned", http)
    }

    private class Ctx(
        val repo: LogRepository,
        val gateway: CannedLogGateway,
        val clusterId: java.util.UUID,
    )

    private fun ctx(handler: () -> Pair<Int, String>): Ctx {
        val fixture = TestClusterFixture(tmp.newFolder())
        val id = runBlocking { fixture.add(kubeconfigFor("http://canned")) }
        val gateway = CannedLogGateway(tmp.newFolder()) { _, _ -> handler() }
        return Ctx(LogRepository(gateway, fixture.repository), gateway, id)
    }

    private val gone = UUID.fromString("00000000-0000-0000-0000-000000000000")

    @Test fun streamResolvesRegisteredContextAndBuildsQuery() = runBlocking {
        val c = ctx { 200 to "hello\n" }
        val events = c.repo.stream(c.clusterId, "web", "web-1", "app", previous = false, follow = true).toList()
        assertEquals(LogLineEvent.StreamEnd(StreamEndReason.Completed), events.last())
        val (path, query) = c.gateway.seen.single()
        assertEquals("/api/v1/namespaces/web/pods/web-1/log", path)
        assertTrue(query, query.contains("container=app"))
        assertTrue(query, query.contains("tailLines=1000"))
        assertTrue(query, query.contains("timestamps=true"))
        assertTrue(query, query.contains("follow=true"))
    }

    @Test fun streamOnMissingConnectionEndsWithConnectionGoneError() = runBlocking {
        val c = ctx { 200 to "" }
        val events = c.repo.stream(gone, "web", "web-1", null, false, false).toList()
        assertEquals(1, events.size)
        val err = ((events[0] as LogLineEvent.StreamEnd).reason as StreamEndReason.Error).error
        assertTrue(err.userMessage(), err is ResourceError.Malformed && "no longer available" in err.userMessage())
    }

    @Test fun saveWritesBodyAndMapsErrors() = runBlocking {
        val canned = "2026-10-25T12:00:00Z one\ntwo\n"
        val ok = ctx { 200 to canned }
        val sink = ByteArrayOutputStream()
        assertNull(ok.repo.save(ok.clusterId, "web", "web-1", "app", previous = false, sink))
        assertEquals(canned, sink.toString(Charsets.UTF_8))

        val forbidden = ctx { 403 to "{}" }
        assertEquals(
            ResourceError.ForbiddenLogs("web"),
            forbidden.repo.save(forbidden.clusterId, "web", "web-1", null, false, ByteArrayOutputStream()),
        )
    }

    @Test fun saveOnMissingConnectionReportsConnectionGone() = runBlocking {
        val c = ctx { 200 to "" }
        val err = c.repo.save(gone, "web", "web-1", null, false, ByteArrayOutputStream())
        assertTrue(err is ResourceError.Malformed && "no longer available" in err.userMessage())
    }

    @Test fun containerValidation() {
        val c = ctx { 200 to "" }
        val regular = listOf("app", "sidecar")
        val init = listOf("init-db")
        assertNull(c.repo.validateContainer(null, regular, init))
        assertNull(c.repo.validateContainer("sidecar", regular, init))
        assertNull(c.repo.validateContainer("init-db", regular, init))
        val err = c.repo.validateContainer("ghost", regular, init)
        assertTrue(err is ResourceError.ContainerNotFound)
        val message = err!!.userMessage()
        assertTrue(message, "ghost" in message && "app" in message && "sidecar" in message && "init-db" in message)
    }
}
