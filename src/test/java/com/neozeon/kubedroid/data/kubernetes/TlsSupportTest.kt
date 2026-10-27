package com.neozeon.kubedroid.data.kubernetes

import com.neozeon.kubedroid.data.local.ClusterRepository
import com.neozeon.kubedroid.data.local.InMemoryClusterRegistry
import com.neozeon.kubedroid.data.local.WallClock
import com.neozeon.kubedroid.data.local.BlobStore
import com.neozeon.kubedroid.data.local.FakeCipherVault
import com.neozeon.kubedroid.data.model.AddResult
import com.neozeon.kubedroid.data.model.ConnectionStatus
import java.io.File
import javax.net.ssl.SSLContext
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 03 Testing: TLS material from kubeconfig PEM/CA data against a
 * real MockWebServer HTTPS endpoint using committed test-only fixtures
 * (throwaway certs; the test CA key in test resources is not a secret).
 */
class TlsSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/tls/$name")).readBytes()

    private val caPem get() = resource("ca-cert.pem")
    private val serverCertPem get() = resource("server-cert.pem")
    private val serverKeyPem get() = resource("server-key.pem")

    private class HttpsServer : AutoCloseable {
        val mock = MockWebServer()
        init {
            mock.useHttps(serverSslContextFromFixtures().socketFactory, false)
            mock.start()
        }
        val url: String get() = "https://127.0.0.1:${mock.port}" // cert SAN: IP 127.0.0.1
        override fun close() {
            runCatching { mock.shutdown() }
        }
    }

    private class Fixture(val dir: File) {
        val registry = InMemoryClusterRegistry()
        val blobStore = BlobStore(dir, FakeCipherVault())
        val parser = KubeconfigParser()
        val provider = ApiClientProvider(blobStore, registry, parser)
        val repository = ClusterRepository(
            registry, blobStore, provider, WallClock { 1L }, parser,
        )
        suspend fun add(raw: String) =
            when (val r = repository.add(raw, "tls-test", "ctx1")) {
                is AddResult.Added -> r.connection.id
                else -> error("add failed: $r")
            }
    }

    private fun httpsKubeconfig(server: String, clusterExtras: String = "") = """
        apiVersion: v1
        kind: Config
        current-context: ctx1
        clusters:
        - name: c1
          cluster:
            server: $server
            $clusterExtras
        contexts:
        - name: ctx1
          context: { cluster: c1, user: u1 }
        users:
        - name: u1
          user:
            token: TLS-TEST-TOKEN
    """.trimIndent()

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    @Test fun pemHelpersParsePkcs8Key() {
        val der = TlsSupport.pemPayload(serverKeyPem, "PRIVATE KEY")
        assertNotNull("PKCS#8 body extracted", der)
        val key = TlsSupport.pkcs8PrivateKey(serverKeyPem)
        assertEquals("RSA", key.algorithm)
    }

    @Test fun x509ChainParses() {
        val chain = TlsSupport.x509Chain(serverCertPem)
        assertEquals(1, chain.size)
        assertTrue(chain[0].subjectX500Principal.name.isNotEmpty())
    }

    @Test fun clientCertMaterialBuilds() {
        // client cert path uses the server fixture as a stand-in cert/key pair
        val (ctx, tm) = TlsSupport.sslMaterial(serverCertPem, serverKeyPem, null, false)
        assertNotNull(ctx.socketFactory)
        assertNotNull(tm)
    }

    @Test fun caDataEnablesTrust() = runTest {
        HttpsServer().use { server ->
            server.mock.enqueue(MockResponse().setBody("{}"))
            val f = Fixture(tmp.newFolder())
            val id = f.add(
                httpsKubeconfig(server.url, "certificate-authority-data: ${b64(caPem)}"),
            )
            val status = f.repository.probe(id)
            assertTrue("CA trust should verify: $status", status is ConnectionStatus.Reachable)
            assertEquals("Bearer TLS-TEST-TOKEN", server.mock.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun selfSignedWithoutCaFailsTls() = runTest {
        HttpsServer().use { server ->
            server.mock.enqueue(MockResponse().setBody("{}"))
            val f = Fixture(tmp.newFolder())
            val id = f.add(httpsKubeconfig(server.url)) // no CA, system trust rejects
            val status = f.repository.probe(id)
            assertTrue("expected TLS failure, got $status", status is ConnectionStatus.Unreachable)
            assertTrue(
                (status as ConnectionStatus.Unreachable).reason.contains("TLS"),
            )
        }
    }

    @Test fun insecureSkipTlsVerifyBypassesTrust() = runTest {
        HttpsServer().use { server ->
            server.mock.enqueue(MockResponse().setBody("{}"))
            val f = Fixture(tmp.newFolder())
            val id = f.add(httpsKubeconfig(server.url, "insecure-skip-tls-verify: true"))
            val status = f.repository.probe(id)
            assertTrue("insecure mode must connect: $status", status is ConnectionStatus.Reachable)
        }
    }

    // --- spec 03 amendment 2026-10-26: PKCS#1 auto-wrap (k0s emits PKCS#1) ---

    private fun pkcs1Fixture(name: String): ByteArray =
        java.util.Base64.getMimeDecoder().decode(
            checkNotNull(
                TlsSupportTest::class.java.getResourceAsStream("/tlsfixtures/$name.b64"),
            ).readBytes().toString(Charsets.US_ASCII).trim(),
        )

    @Test fun rsaPkcs1AutoWrapsToPkcs8() {
        val wrapped = TlsSupport.pkcs8PrivateKey(pkcs1Fixture("rsa_pkcs1"))
        val direct = TlsSupport.pkcs8PrivateKey(pkcs1Fixture("rsa_pkcs8"))
        assertEquals("RSA", wrapped.algorithm)
        assertEquals(
            "PKCS#1 wrap must produce the same key as openssl pkcs8",
            (direct as java.security.interfaces.RSAPrivateKey).modulus,
            (wrapped as java.security.interfaces.RSAPrivateKey).modulus,
        )
    }

    @Test fun ecPkcs1AutoWrapsToPkcs8() {
        val wrapped = TlsSupport.pkcs8PrivateKey(pkcs1Fixture("ec_pkcs1"))
        val direct = TlsSupport.pkcs8PrivateKey(pkcs1Fixture("ec_pkcs8"))
        assertEquals("EC", wrapped.algorithm)
        assertEquals(
            "SEC1 wrap must produce the same key as openssl pkcs8",
            (direct as java.security.interfaces.ECPrivateKey).s,
            (wrapped as java.security.interfaces.ECPrivateKey).s,
        )
    }

    @Test fun unsupportedPemStillRejected() {
        val junk = ("-----BEGIN ENCRYPTED PRIVATE KEY-----\nQUJD\n" +
            "-----END ENCRYPTED PRIVATE KEY-----").toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            TlsSupport.pkcs8PrivateKey(junk)
        }
    }

}

private fun tlsResource(name: String): ByteArray =
    checkNotNull(TlsSupportTest::class.java.getResourceAsStream("/tls/$name")).readBytes()

private fun serverSslContextFromFixtures(): SSLContext {
    val (ctx, _) = TlsSupport.sslMaterial(
        certPem = tlsResource("server-cert.pem"),
        keyPem = tlsResource("server-key.pem"),
        caPem = null,
        insecureSkipTlsVerify = false,
    )
    return ctx

}
