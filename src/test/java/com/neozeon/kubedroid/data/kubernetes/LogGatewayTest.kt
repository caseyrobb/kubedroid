package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.data.model.ResourceError
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 05 stage 2 Testing: the streaming log gateway against MockWebServer,
 * via the REAL ApiClientProvider fixture (auth inherited, like production).
 * Covers URL/query construction, timestamp split, line cap,
 * retry-before-first-byte / no-retry-after, follow timeout derivation,
 * save streaming, and responsive mid-stream cancellation.
 */
class LogGatewayTest {

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
            f.add(kubeconfigFor(server.url("/").toString().trimEnd('/'), "token: LOG-TOKEN"))
        }
    }

    private fun gateway(f: Fixture, delays: MutableList<Long> = mutableListOf()) =
        LogGateway(f.f.provider) { delays += it }

    private fun streamReq(
        f: Fixture,
        follow: Boolean = false,
        previous: Boolean = false,
        container: String? = "app",
    ) = LogRequest(f.id, "ctx1", "web", "web-1", container, previous, follow)

    private suspend fun awaitRequests(server: MockWebServer, count: Int) =
        withTimeout(5_000) { while (server.requestCount < count) delay(20) }

    /** Throttled body: streams ~[bytesPerSecond] bytes/s, holding the connection open. */
    private fun throttled(lines: List<String>, bytesPerSecond: Int = 4): MockResponse =
        MockResponse()
            .setBody(Buffer().writeUtf8(lines.joinToString("\n") + "\n"))
            .throttleBody(bytesPerSecond.toLong(), 1, TimeUnit.SECONDS)

    // ---- stream shape + parsing ----

    @Test fun streamRequestShapeHeadersAndParsedLines() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody(
            "2026-10-25T12:00:00.123Z hello world\n" +
                "plain line\n" +
                "partial-no-newline",
        ))
        val f = Fixture(server)
        val events = withTimeout(5_000) { gateway(f).stream(streamReq(f)).toList() }

        val recorded = server.takeRequest()
        assertEquals(
            "/api/v1/namespaces/web/pods/web-1/log?container=app&tailLines=1000&timestamps=true",
            recorded.path,
        )
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals("Bearer LOG-TOKEN", recorded.getHeader("Authorization"))

        assertEquals(LogLine("2026-10-25T12:00:00.123Z", "hello world"),
            (events[0] as LogLineEvent.Line).line)
        assertEquals(LogLine(null, "plain line"), (events[1] as LogLineEvent.Line).line)
        assertEquals(LogLine(null, "partial-no-newline"), (events[2] as LogLineEvent.Line).line)
        assertEquals(LogLineEvent.StreamEnd(StreamEndReason.Completed), events[3])
    }

    @Test fun previousAndFollowAddQueryParams() = runBlocking {
        val server = newServer()
        server.enqueue(throttled(listOf("live", "hold", "more")))
        val f = Fixture(server)
        val collector = launch {
            runCatching { withTimeout(10_000) { gateway(f).stream(streamReq(f, follow = true, previous = true)).toList() } }
        }
        awaitRequests(server, 1)
        collector.cancelAndJoin()

        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("previous=true"))
        assertTrue(path, path.contains("follow=true"))
        assertTrue(path, path.contains("tailLines=1000"))
        assertTrue(path, path.endsWith("follow=true"))
    }

    @Test fun emptyBodyEndsCompletedWithoutLines() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody(""))
        val f = Fixture(server)
        val events = withTimeout(5_000) { gateway(f).stream(streamReq(f)).toList() }
        assertEquals(listOf<LogLineEvent>(LogLineEvent.StreamEnd(StreamEndReason.Completed)), events)
    }

    // ---- parseLine unit behaviour (no server) ----

    @Test fun parseLineSplitsTimestampsAndPassesThroughRaw() {
        val split = LogGateway.parseLine("2026-10-25T12:00:00+02:00 prefixed")
        assertEquals("2026-10-25T12:00:00+02:00", split.timestamp)
        assertEquals("prefixed", split.text)

        val raw = LogGateway.parseLine("no timestamp here")
        assertNull(raw.timestamp)
        assertEquals("no timestamp here", raw.text)

        // A line that merely starts with date-ish junk must not be mis-split.
        val fake = LogGateway.parseLine("2026-13-99 not-a-timestamp")
        assertNull(fake.timestamp)
        assertEquals("2026-13-99 not-a-timestamp", fake.text)
    }

    @Test fun parseLineCapsLongTextWithMarker() {
        val long = LogGateway.parseLine("2026-10-25T12:00:00Z " + "x".repeat(5_000))
        assertEquals(LogGateway.MAX_LINE_CHARS + "…[truncated]".length, long.text.length)
        assertTrue(long.text.endsWith("…[truncated]"))
    }

    // ---- retry policy ----

    @Test fun retryOnServerErrorBeforeFirstByte() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setBody("recovered\n"))
        val f = Fixture(server)
        val delays = mutableListOf<Long>()
        val events = withTimeout(5_000) { gateway(f, delays).stream(streamReq(f)).toList() }

        assertEquals(listOf(500L), delays)
        assertEquals(LogLine(null, "recovered"), (events[0] as LogLineEvent.Line).line)
        assertEquals(2, server.requestCount)
    }

    @Test fun noRetryAfterFirstLineThenDrop() = runBlocking {
        val server = newServer()
        // DISCONNECT_DURING_RESPONSE_BODY sends roughly half the body then
        // closes: the first line is always flushed, the drop always lands
        // mid-stream (the filler guarantees the split point is past it).
        server.enqueue(
            MockResponse()
                .setBody("first\n" + "a".repeat(2_000) + "\nlast\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val f = Fixture(server)
        val delays = mutableListOf<Long>()
        val events = withTimeout(5_000) { gateway(f, delays).stream(streamReq(f)).toList() }

        val firstLine = events.first() as LogLineEvent.Line
        assertEquals("first", firstLine.line.text)
        val end = events.last() as LogLineEvent.StreamEnd
        assertTrue("mid-stream drop must surface Network, got $end",
            (end.reason as StreamEndReason.Error).error is ResourceError.Network)
        assertEquals("no retry after the first line was emitted", 1, server.requestCount)
        assertEquals(emptyList<Long>(), delays)
    }

    @Test fun forbiddenEndsWithoutRetry() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val f = Fixture(server)
        val events = withTimeout(5_000) { gateway(f).stream(streamReq(f)).toList() }

        assertEquals(1, events.size)
        val err = ((events[0] as LogLineEvent.StreamEnd).reason as StreamEndReason.Error).error
        assertEquals(ResourceError.ForbiddenLogs("web"), err)
        assertEquals(1, server.requestCount)
    }

    // ---- follow timeout derivation (spec Configuration, wall-clock-free) ----

    @Test fun followDerivesZeroTimeoutsStaticInherits() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setBody("x\n"))
        val f = Fixture(server)
        val captured = mutableListOf<Pair<Boolean, OkHttpClient>>()
        val spy = object : LogGateway(f.f.provider, RetryBackoff { }) {
            override fun deriveHttp(resolved: Resolved, follow: Boolean): OkHttpClient =
                super.deriveHttp(resolved, follow).also { captured += follow to it }
        }
        withTimeout(5_000) { spy.stream(streamReq(f, follow = false)).toList() }

        server.enqueue(throttled(listOf("y", "hold", "more")))
        val collector = launch {
            runCatching { withTimeout(10_000) { spy.stream(streamReq(f, follow = true)).toList() } }
        }
        awaitRequests(server, 2)
        collector.cancelAndJoin()

        assertEquals(2, captured.size)
        val static = captured[0].second
        val follow = captured[1].second
        assertEquals(0L, follow.readTimeoutMillis.toLong())
        assertEquals(0L, follow.callTimeoutMillis.toLong())
        val base = withTimeout(5_000) { spy.resolve(f.id, "ctx1").http }
        assertEquals(base.readTimeoutMillis.toLong(), static.readTimeoutMillis.toLong())
    }

    // ---- cancellation ----

    @Test fun cancelMidStreamCancelsLiveCall() = runBlocking {
        val server = newServer()
        // 4 B/s: the first line lands at once, then the connection stays
        // open while the server dribbles bytes — the reader is blocked in
        // socket read when the collector cancels.
        server.enqueue(throttled(listOf("one", "second line dribbles slowly", "third")))
        val f = Fixture(server)
        val captured = AtomicReference<Call?>()
        val gw = object : LogGateway(f.f.provider, RetryBackoff { }) {
            override suspend fun resolve(connectionId: java.util.UUID, context: String): Resolved {
                val r = super.resolve(connectionId, context)
                return Resolved(r.basePath, r.http.newBuilder().addInterceptor { chain ->
                    captured.set(chain.call())
                    chain.proceed(chain.request())
                }.build())
            }
        }
        // first() cancels the collector as soon as one line arrives.
        val line = withTimeout(5_000) { gw.stream(streamReq(f, follow = true)).first() }
        assertEquals(LogLine(null, "one"), (line as LogLineEvent.Line).line)

        withTimeout(5_000) {
            while (captured.get()?.isCanceled() != true) delay(25)
        }
    }

    // ---- save path ----

    @Test fun saveStreamsFullBodyAndOmitsTailAndFollow() = runBlocking {
        val server = newServer()
        val canned = "2026-10-25T12:00:00Z line one\nline two\n" + "z".repeat(20_000) + "\n"
        server.enqueue(MockResponse().setBody(canned))
        val f = Fixture(server)
        val sink = ByteArrayOutputStream()
        val error = gateway(f).save(
            SaveRequest(f.id, "ctx1", "web", "web-1", "app", previous = true),
            sink,
        )
        assertNull(error)
        assertEquals(canned, sink.toString(Charsets.UTF_8))
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/api/v1/namespaces/web/pods/web-1/log?"))
        assertTrue(path, path.contains("container=app"))
        assertTrue(path, path.contains("previous=true"))
        assertTrue(path, path.contains("timestamps=true"))
        assertTrue("save must omit tailLines: $path", !path.contains("tailLines"))
        assertTrue("save must omit follow: $path", !path.contains("follow"))
    }

    @Test fun saveMapsForbidden() = runBlocking {
        val server = newServer()
        server.enqueue(MockResponse().setResponseCode(403))
        val f = Fixture(server)
        val error = gateway(f).save(SaveRequest(f.id, "ctx1", "web", "web-1", null), ByteArrayOutputStream())
        assertEquals(ResourceError.ForbiddenLogs("web"), error)
    }

    @Test fun mapHttpTable() {
        assertEquals(ResourceError.Unauthorized, LogGateway.mapHttp(401, "web", "web-1"))
        assertEquals(ResourceError.ForbiddenLogs("web"), LogGateway.mapHttp(403, "web", "web-1"))
        assertEquals(ResourceError.NotFound("Pod", "web-1"), LogGateway.mapHttp(404, "web", "web-1"))
        assertEquals(ResourceError.Server(503), LogGateway.mapHttp(503, "web", "web-1"))
    }
}
