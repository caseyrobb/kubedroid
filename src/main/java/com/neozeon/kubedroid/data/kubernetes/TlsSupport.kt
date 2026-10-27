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
     * @param keyPem  PKCS#8 private key PEM, or PKCS#1 RSA/EC PEM which is
     *                auto-wrapped into PKCS#8 (k0s emits PKCS#1)
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
        pemPayload(keyPem, "PRIVATE KEY")?.let { return keyFactoryFromPkcs8(it) }
        pemPayload(keyPem, "RSA PRIVATE KEY")?.let {
            return keyFactoryFromPkcs8(wrapPkcs1Rsa(it))
        }
        pemPayload(keyPem, "EC PRIVATE KEY")?.let { der ->
            val curveOid = ecCurveOidTlv(der)
                ?: throw IllegalArgumentException("EC private key missing curve OID")
            return keyFactoryFromPkcs8(wrapPkcs1Ec(der, curveOid))
        }
        throw IllegalArgumentException("not a supported PEM private key")
    }

    private fun keyFactoryFromPkcs8(der: ByteArray): java.security.PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .recoverCatching { KeyFactory.getInstance("EC").generatePrivate(spec) }
            .getOrElse { throw IllegalArgumentException("unsupported key algorithm", it) }
    }

    // --- minimal DER helpers: wrap a PKCS#1 key body into PKCS#8
    //     PrivateKeyInfo { version 0, AlgorithmIdentifier, OCTET STRING pkcs1 } ---

    private fun derLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }

    private fun derTlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(body.size) + body

    private fun derSequence(vararg elements: ByteArray): ByteArray =
        derTlv(0x30, elements.fold(ByteArray(0)) { a, b -> a + b })

    // rsaEncryption 1.2.840.113549.1.1.1, NULL params
    private val RSA_ALGID_BODY = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
        0x0D, 0x01, 0x01, 0x01,
        0x05, 0x00,
    )

    // ecPublicKey 1.2.840.10045.2.1
    private val EC_PUBKEY_OID = byteArrayOf(
        0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
    )

    private fun wrapPkcs1Rsa(pkcs1: ByteArray): ByteArray = derSequence(
        byteArrayOf(0x02, 0x01, 0x00), // version 0
        derSequence(RSA_ALGID_BODY),
        derTlv(0x04, pkcs1),
    )

    private fun wrapPkcs1Ec(pkcs1: ByteArray, curveOidTlv: ByteArray): ByteArray = derSequence(
        byteArrayOf(0x02, 0x01, 0x00),
        derSequence(EC_PUBKEY_OID, curveOidTlv),
        derTlv(0x04, pkcs1),
    )

    /** Walks SEC1 ECPrivateKey children to find the [0] curve OID TLV. */
    private fun ecCurveOidTlv(sec1: ByteArray): ByteArray? {
        // sec1 is the full SEC1 DER: SEQUENCE { ver, priv, [0] { curveOID }, [1] { pub } }
        val outer = derChildren(sec1) ?: return null
        val body = outer.firstOrNull { (tag, _) -> tag == 0x30 }?.second ?: return null
        val ctx0 = derChildren(body)?.firstOrNull { (tag, _) -> tag == 0xA0 } ?: return null
        return derChildren(ctx0.second)?.firstOrNull { (tag, _) -> tag == 0x06 }
            ?.let { (tag, b) -> byteArrayOf(tag.toByte()) + derLength(b.size) + b }
    }

    /** Parses the direct TLV children of a DER SEQUENCE body. */
    private fun derChildren(body: ByteArray): List<Pair<Int, ByteArray>>? {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 1 < body.size) {
            val tag = body[i].toInt() and 0xFF
            var len = body[i + 1].toInt() and 0xFF
            var off = i + 2
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || n > 3 || off + n > body.size) return null
                len = 0
                repeat(n) { len = (len shl 8) or (body[off++].toInt() and 0xFF) }
            }
            if (len < 0 || off + len > body.size) return null
            out += tag to body.copyOfRange(off, off + len)
            i = off + len
        }
        return out
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
