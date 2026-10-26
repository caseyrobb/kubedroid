package com.neozeon.kubedroid.data.kubernetes

import io.kubernetes.client.openapi.ApiClient
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Spec 04 "ResourceGateway": generic Kubernetes REST access — one OkHttp GET
 * shape for every catalog type, riding the already-authenticated OkHttp
 * client from [ApiClientProvider] (auth interceptor inherited, nothing new
 * to configure). GET only in this spec: no mutation surface exists here.
 */
/** Injectable retry backoff (spec 04 "Reliability"); tests use a no-op. */
fun interface RetryBackoff {
    suspend fun await(delayMs: Long)
}

open class ResourceGateway @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    /** Injectable backoff for tests; production uses real delays. */
    private val backoff: RetryBackoff = RetryBackoff { delay(it) },
) {
    sealed interface Result {
        data class Ok(val json: Map<String, Any?>) : Result
        data class Http(val code: Int, val bodySnippet: String) : Result
        data class Network(val detail: String) : Result
        data class Malformed(val detail: String) : Result
    }

    internal data class Resolved(val basePath: String, val http: okhttp3.OkHttpClient)

    /** Internal seam so unit tests can pin resolution; production uses the cache. */
    internal open suspend fun resolve(connectionId: UUID, context: String): Resolved {
        val api: ApiClient = apiClientProvider.clientFor(connectionId, context)
        return Resolved(api.basePath.trimEnd('/'), api.httpClient)
    }

    suspend fun list(
        connectionId: UUID,
        context: String,
        entry: CatalogEntry,
        namespace: String?,
        limit: Long = PAGE_SIZE,
        continueToken: String? = null,
    ): Result {
        val resolved = resolve(connectionId, context)
        val url = buildUrl(resolved.basePath, ResourceCatalog.listPath(entry, namespace), limit, continueToken)
        return executeWithRetry(resolved.http, url)
    }

    suspend fun get(
        connectionId: UUID,
        context: String,
        entry: CatalogEntry,
        namespace: String?,
        name: String,
    ): Result {
        val resolved = resolve(connectionId, context)
        val url = buildUrl(resolved.basePath, ResourceCatalog.getPath(entry, namespace, name), limit = null, null)
        return executeWithRetry(resolved.http, url)
    }

    private fun buildUrl(basePath: String, path: String, limit: Long?, continueToken: String?): String {
        var builder = (basePath + path).toHttpUrl().newBuilder()
        if (limit != null) builder = builder.addQueryParameter("limit", limit.toString())
        if (continueToken != null) builder = builder.addQueryParameter("continue", continueToken)
        return builder.build().toString()
    }

    private suspend fun executeWithRetry(http: okhttp3.OkHttpClient, url: String): Result {
        var attempts = 0
        while (true) {
            val outcome = try {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.code in 200..299 -> parseJson(body)
                        response.code in 500..599 -> Retryable(response.code, body)
                        else -> Result.Http(response.code, body.take(BODY_SNIPPET))
                    }
                }
            } catch (e: IOException) {
                RetryableNetwork(e.message ?: "io error")
            }
            val shouldRetry = outcome is Retryable || outcome is RetryableNetwork
            if (!shouldRetry) return (outcome as Result)
            // spec 04: max 2 retries, 500 ms / 2 s; never retry a cancelled scope.
            if (attempts >= MAX_RETRIES || !currentCoroutineContext().isActive) {
                return when (outcome) {
                    is Retryable -> Result.Http(outcome.code, outcome.body.take(BODY_SNIPPET))
                    is RetryableNetwork -> Result.Network(outcome.detail)
                    else -> error("unreachable")
                }
            }
            backoff.await(BACKOFF_MS[attempts])
            attempts++
        }
    }

    private class Retryable(val code: Int, val body: String)
    private class RetryableNetwork(val detail: String)

    private fun parseJson(body: String): Result = try {
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(body, MAP_TYPE) as? Map<String, Any?>
            ?: return Result.Malformed("top level is not an object")
        Result.Ok(map)
    } catch (e: Exception) {
        Result.Malformed(e.message ?: "json parse failed")
    }

    companion object {
        const val PAGE_SIZE = 500L
        private const val MAX_RETRIES = 2
        private const val BODY_SNIPPET = 300
        private val BACKOFF_MS = longArrayOf(500L, 2_000L)
        private val gson = Gson()
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type
    }
}
