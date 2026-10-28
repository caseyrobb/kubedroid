package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.data.model.ResourceError
import io.kubernetes.client.openapi.ApiClient
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One parsed log line (spec 05): server timestamp prefix split from text. */
data class LogLine(val timestamp: String?, val text: String)

/** Why a follow/static stream stopped (spec 05 "LogGateway"). */
sealed class StreamEndReason {
    data object Completed : StreamEndReason()
    data class Error(val error: ResourceError) : StreamEndReason()
}

sealed interface LogLineEvent {
    data class Line(val line: LogLine) : LogLineEvent
    data class StreamEnd(val reason: StreamEndReason) : LogLineEvent
}

/** Viewer stream request: always tailLines=1000 + timestamps=true. */
data class LogRequest(
    val connectionId: UUID,
    val context: String,
    val namespace: String,
    val podName: String,
    val container: String?,
    val previous: Boolean = false,
    val follow: Boolean = false,
)

/** Save request: complete log — no tailLines, no follow (spec 05 "Save"). */
data class SaveRequest(
    val connectionId: UUID,
    val context: String,
    val namespace: String,
    val podName: String,
    val container: String?,
    val previous: Boolean = false,
)

/**
 * Spec 05 "LogGateway": streaming pod-log access over the authenticated
 * OkHttp client from [ApiClientProvider] — same resolve() seam as
 * [ResourceGateway], but the response body is consumed line-by-line instead
 * of parsed as JSON, and cancellation is guaranteed responsive even on a
 * silent follow stream: collector cancellation cancels the live OkHttp call,
 * closing the socket so the blocking read unwinds immediately.
 *
 * Retry policy (spec 05): IOException/5xx are retried at most twice with
 * [RetryBackoff] **before the first line is emitted**; after that every
 * failure ends the stream — reconnect is a user action.
 */
open class LogGateway @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val backoff: RetryBackoff = RetryBackoff { kotlinx.coroutines.delay(it) },
) {
    internal data class Resolved(val basePath: String, val http: OkHttpClient)

    /** Internal seam so unit tests can pin resolution; production uses the cache. */
    internal open suspend fun resolve(connectionId: UUID, context: String): Resolved {
        val api: ApiClient = apiClientProvider.clientFor(connectionId, context)
        return Resolved(api.basePath.trimEnd('/'), api.httpClient)
    }

    /** Cold flow: each collection performs one HTTP exchange (with pre-first-byte retries). */
    fun stream(request: LogRequest): Flow<LogLineEvent> = callbackFlow {
        val resolved = resolve(request.connectionId, request.context)
        val url = buildUrl(resolved.basePath, request.namespace, request.podName,
            request.container, request.previous) {
            addQueryParameter("tailLines", TAIL_LINES.toString())
            addQueryParameter("timestamps", "true")
            if (request.follow) addQueryParameter("follow", "true")
        }
        // Follow streams must not trip the inherited read timeout on silence;
        // static tails keep the production timeouts (spec "Follow timeouts").
        val http = deriveHttp(resolved, request.follow)
        val httpRequest = Request.Builder().url(url).header("Accept", "application/json").get().build()

        val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)

        val reader = launch(Dispatchers.IO) {
            var attempts = 0
            var emitted = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val call = http.newCall(httpRequest)
                currentCall.set(call)
                var retry = false
                try {
                    call.execute().use { response ->
                        when {
                            response.code in 200..299 -> {
                                val source = response.body?.source()
                                while (true) {
                                    val raw = source?.readUtf8Line() ?: break
                                    emitted = true
                                    trySend(LogLineEvent.Line(parseLine(raw)))
                                }
                                trySend(LogLineEvent.StreamEnd(StreamEndReason.Completed))
                            }

                            response.code in 500..599 && attempts < MAX_RETRIES && !emitted ->
                                retry = true

                            else -> trySend(
                                LogLineEvent.StreamEnd(
                                    StreamEndReason.Error(
                                        mapHttp(response.code, request.namespace, request.podName),
                                    ),
                                ),
                            )
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (io: IOException) {
                    if (attempts < MAX_RETRIES && !emitted) retry = true
                    else trySend(LogLineEvent.StreamEnd(StreamEndReason.Error(ResourceError.Network(io.message))))
                }
                if (!retry) break
                backoff.await(BACKOFF_MS[attempts])
                attempts++
            }
            close()
        }
        // If the reader dies without closing (unexpected throw), don't hang the flow.
        reader.invokeOnCompletion { close() }
        // awaitClose's handler runs when the collector cancels: cancelling the
        // live call closes the socket so the blocking read unwinds at once —
        // responsive cancellation even on a silent follow stream (spec 05).
        awaitClose { currentCall.get()?.cancel() }
    }

    /**
     * Save path (spec 05): one-shot GET with no tailLines/follow, body copied
     * to [sink] in 8 KB chunks — constant memory, raw passthrough (no display
     * truncation, server timestamps included). Returns null on success or the
     * mapped [ResourceError]; cancellation cancels the call (Uri truncation
     * back to zero bytes is the caller's job — the caller owns the sink).
     */
    suspend fun save(request: SaveRequest, sink: OutputStream): ResourceError? {
        val resolved = resolve(request.connectionId, request.context)
        val url = buildUrl(resolved.basePath, request.namespace, request.podName,
            request.container, request.previous) {
            addQueryParameter("timestamps", "true")
        }
        val httpRequest = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val call = resolved.http.newCall(httpRequest)
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        return try {
            call.execute().use { response ->
                if (response.code !in 200..299) {
                    return@use mapHttp(response.code, request.namespace, request.podName)
                }
                val source = response.body?.source() ?: return@use null
                val buffer = ByteArray(SAVE_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
                sink.flush()
                null
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (io: IOException) {
            ResourceError.Network(io.message)
        }
    }

    // ---- helpers ----

    /** Test-visible seam: follow calls disable read/call timeouts (spec Configuration). */
    internal open fun deriveHttp(resolved: Resolved, follow: Boolean): OkHttpClient =
        if (follow) {
            resolved.http.newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        } else resolved.http

    private fun buildUrl(
        basePath: String,
        namespace: String,
        podName: String,
        container: String?,
        previous: Boolean,
        extra: okhttp3.HttpUrl.Builder.() -> Unit,
    ): String {
        val entry = requireNotNull(ResourceCatalog.find("v1", "pods"))
        var builder = (basePath + ResourceCatalog.logPath(entry, namespace, podName)).toHttpUrl().newBuilder()
        if (container != null) builder = builder.addQueryParameter("container", container)
        if (previous) builder = builder.addQueryParameter("previous", "true")
        builder.apply(extra)
        return builder.build().toString()
    }

    internal companion object {
        const val TAIL_LINES = 1000L
        const val SAVE_CHUNK_BYTES = 8 * 1024
        private const val MAX_RETRIES = 2
        private val BACKOFF_MS = longArrayOf(500L, 2_000L)
        const val MAX_LINE_CHARS = 4096
        private const val TRUNCATION_MARKER = "…[truncated]"

        /** RFC3339 timestamp + single space prefix (what the API sends with timestamps=true). */
        private val TIMESTAMP_PREFIX = Regex(
            """^(\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:[Zz]|[+-]\d{2}:\d{2})) (.*)$""",
        )

        /** Split + cap one raw line for display (spec 05 "LogGateway"). */
        fun parseLine(raw: String): LogLine {
            val match = TIMESTAMP_PREFIX.matchEntire(raw)
            val (timestamp, text) = if (match != null) {
                match.groupValues[1] to match.groupValues[2]
            } else null to raw
            val capped = if (text.length > MAX_LINE_CHARS) {
                text.take(MAX_LINE_CHARS) + TRUNCATION_MARKER
            } else text
            return LogLine(timestamp, capped)
        }

        /** HTTP code → ResourceError for the pods/log resource (spec 05 error table). */
        fun mapHttp(code: Int, namespace: String, podName: String): ResourceError = when (code) {
            401 -> ResourceError.Unauthorized
            403 -> ResourceError.ForbiddenLogs(namespace)
            404 -> ResourceError.NotFound("Pod", podName)
            else -> ResourceError.Server(code)
        }
    }
}
