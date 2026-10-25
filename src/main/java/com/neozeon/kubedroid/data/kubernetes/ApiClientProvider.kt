package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.registry.ClusterRegistry
import io.kubernetes.client.openapi.ApiClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class ConnectionNotFoundException(connectionId: java.util.UUID) :
    IOException("no stored connection $connectionId")

private data class CacheKey(val connectionId: java.util.UUID, val context: String)

/**
 * Builds and caches one configured [ApiClient] per (connection, context)
 * (spec 03 "ApiClientProvider"). LRU max 4, never persisted; decrypted
 * bytes are zeroized after construction.
 */
@Singleton
class ApiClientProvider @Inject constructor(
    private val blobStore: BlobStore,
    private val registry: ClusterRegistry,
    private val parser: KubeconfigParser,
) {
    private val cache = object : LinkedHashMap<CacheKey, ApiClient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ApiClient>) =
            size > MAX_CACHE
    }

    suspend fun clientFor(connectionId: java.util.UUID, context: String): ApiClient {
        val key = CacheKey(connectionId, context)
        synchronized(cache) { cache[key]?.let { return it } }

        val raw = withContext(Dispatchers.IO) { blobStore.read(connectionId) }
            ?: throw ConnectionNotFoundException(connectionId)
        try {
            val client = buildClient(raw, connectionId, context)
            synchronized(cache) { cache[key] = client }
            return client
        } finally {
            raw.fill(0)
        }
    }

    /** Drop every cached client for [connectionId] (delete path). */
    fun evict(connectionId: java.util.UUID) {
        synchronized(cache) {
            cache.keys.filter { it.connectionId == connectionId }.forEach { cache.remove(it) }
        }
    }

    private fun buildClient(
        rawBytes: ByteArray,
        connectionId: java.util.UUID,
        context: String,
    ): ApiClient {
        val text = rawBytes.toString(Charsets.UTF_8)
        val parsed = when (val r = parser.parse(text)) {
            is ParseResult.Valid -> r.config
            is ParseResult.Invalid -> throw IOException("stored kubeconfig failed validation: ${r.error.userMessage()}")
        }
        val ctx = parsed.contexts.firstOrNull { it.name == context }
            ?: throw IOException("stored kubeconfig has no context '$context'")
        val cluster = parsed.clusters.first { it.name == ctx.clusterName }
        val user = ctx.userName?.let { name -> parsed.users.firstOrNull { it.name == name } }

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor(user?.auth))
            .apply {
                val certPem = (user?.auth as? AuthMethod.ClientCert)
                    ?.let { java.util.Base64.getMimeDecoder().decode(it.certData.expose()) }
                val keyPem = (user?.auth as? AuthMethod.ClientCert)
                    ?.let { java.util.Base64.getMimeDecoder().decode(it.keyData.expose()) }
                val caPem = cluster.caCertData
                    ?.let { java.util.Base64.getMimeDecoder().decode(it.expose()) }
                val (sslContext, trustManager) = TlsSupport.sslMaterial(
                    certPem = certPem,
                    keyPem = keyPem,
                    caPem = caPem,
                    insecureSkipTlsVerify = cluster.insecureSkipTlsVerify,
                )
                sslSocketFactory(sslContext.socketFactory, trustManager)
            }
            .build()

        return ApiClient().apply {
            // ORDER MATTERS: setVerifyingSsl rebuilds the OkHttp client from
            // ApiClient's own defaults — it must run BEFORE setHttpClient or
            // the custom TLS material above is silently discarded.
            setVerifyingSsl(!cluster.insecureSkipTlsVerify)
            setBasePath(cluster.server)
            setHttpClient(okHttp)
        }
    }

    private fun authInterceptor(auth: AuthMethod?): Interceptor = Interceptor { chain ->
        val builder = chain.request().newBuilder()
        when (auth) {
            is AuthMethod.BearerToken ->
                builder.header("Authorization", "Bearer ${auth.token.expose()}")
            is AuthMethod.OidcIdToken ->
                builder.header("Authorization", "Bearer ${auth.idToken.expose()}")
            is AuthMethod.Basic ->
                builder.header("Authorization", Credentials.basic(auth.username, auth.password.expose()))
            else -> builder
        }
        chain.proceed(builder.build())
    }

    companion object {
        // pinned in spec 03 "Configuration"
        const val CONNECT_TIMEOUT_SEC = 15L
        const val READ_TIMEOUT_SEC = 30L
        const val MAX_CACHE = 4
    }
}
