package com.neozeon.kubedroid.data.kubernetes

import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS material construction from parsed kubeconfig data (spec 03
 * "ApiClientProvider"). PEM parsing is deliberately minimal: only what
 * kubeconfigs actually carry.
 */
object TlsSupport {

    fun x509Chain(certPem: ByteArray): List<X509Certificate> =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(certPem.inputStream())
            .filterIsInstance<X509Certificate>()

    fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * @param certPem client certificate chain PEM (null when not using certs)
     * @param keyPem  PKCS#8 private key PEM (parser already rejected PKCS#1)
     * @param caPem   optional CA bundle; null + not insecure ⇒ system defaults
     */
    fun sslMaterial(
        certPem: ByteArray?,
        keyPem: ByteArray?,
        caPem: ByteArray?,
        insecureSkipTlsVerify: Boolean,
    ): Pair<SSLContext, X509TrustManager> {
        val kms: Array<javax.net.ssl.KeyManager>? = if (certPem != null && keyPem != null) {
            val chain = x509Chain(certPem)
            val key = pkcs8PrivateKey(keyPem)
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("client", key, CharArray(0), chain.toTypedArray())
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, CharArray(0)) }
                .keyManagers
        } else {
            null
        }

        val tms: Array<TrustManager> = when {
            insecureSkipTlsVerify -> arrayOf(trustAllManager())
            caPem != null -> {
                val caStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    x509Chain(caPem).forEachIndexed { i, cert ->
                        setCertificateEntry("ca-$i", cert)
                    }
                }
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(caStore) }
                    .trustManagers
            }
            else -> TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
        }

        val context = SSLContext.getInstance("TLS").apply { init(kms, tms, null) }
        val trustManager = tms.filterIsInstance<X509TrustManager>().first()
        return context to trustManager
    }

    fun pkcs8PrivateKey(keyPem: ByteArray): java.security.PrivateKey {
        val der = pemPayload(keyPem, "PRIVATE KEY")
            ?: throw IllegalArgumentException("not a PKCS#8 PEM private key")
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .recoverCatching { KeyFactory.getInstance("EC").generatePrivate(spec) }
            .getOrElse { throw IllegalArgumentException("unsupported key algorithm", it) }
    }

    /** Base64-decodes the body of the first PEM block of [label]. */
    fun pemPayload(pem: ByteArray, label: String): ByteArray? {
        val text = pem.toString(Charsets.US_ASCII)
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = text.indexOf(begin).takeIf { it >= 0 } ?: return null
        val stop = text.indexOf(end, start).takeIf { it >= 0 } ?: return null
        val body = text.substring(start + begin.length, stop)
            .filterNot(Char::isWhitespace)
        return java.util.Base64.getMimeDecoder().decode(body)
    }
}
