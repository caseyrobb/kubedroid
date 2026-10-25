package com.neozeon.kubedroid.qrexport

import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ParseResult
import com.neozeon.kubedroid.data.qr.QrChunkCodec
import com.neozeon.kubedroid.data.qr.QrEnvelopeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 03 Testing (stage 7): the export→scan loop, automated. The fixture
 * goes through the REAL stripper → app envelope/chunk codecs, the chunks are
 * fed strictly in order to the app's own Decoder, and the reassembled
 * kubeconfig must import through the app's KubeconfigParser — the same code
 * paths the scanner UI drives.
 */
class ExportLoopTest {

    private val caB64 = java.util.Base64.getEncoder()
        .encodeToString(("ACCEPTANCE-CA-PEM" + "x".repeat(1200)).toByteArray())

    private val fixture = """
        apiVersion: v1
        kind: Config
        clusters:
        - name: c1
          cluster:
            server: https://10.0.2.2:6443
            certificate-authority-data: $caB64
        - name: c2
          cluster:
            server: https://127.0.0.1:6443
        users:
        - name: token-user
          user:
            token: 0f3a9c7e2b8d41e6
        - name: exec-user
          user:
            exec:
              apiVersion: client.authentication.k8s.io/v1beta1
              command: kubelogin
        contexts:
        - name: east
          context:
            cluster: c1
            user: token-user
        - name: local
          context:
            cluster: c2
            user: exec-user
        current-context: east
    """.trimIndent()

    @Test fun fixtureWithoutStripIsRejectedByTheAppParser() {
        // Premise of the stripper: the raw exec kubeconfig must NOT import.
        val parsed = KubeconfigParser().parse(fixture)
        assertTrue("raw fixture must be Invalid (exec), got $parsed",
            parsed is ParseResult.Invalid)
    }

    @Test fun strippedFixtureImportsCleanly() {
        val stripped = KubeconfigStripper.strip(fixture)
        val parsed = KubeconfigParser().parse(stripped.yaml)
        assertTrue("stripped fixture must be Valid, got $parsed", parsed is ParseResult.Valid)
        val config = (parsed as ParseResult.Valid).config
        assertEquals("east", config.currentContextName)
        assertEquals(2, config.clusters.size)
    }

    @Test fun chunksReassembleThroughTheAppDecoderIntoAnImportableConfig() {
        val stripped = KubeconfigStripper.strip(fixture)
        val envelope = QrEnvelopeCodec.encode(stripped.yaml)
        val chunks = QrChunkCodec.encode(envelope, maxChars = 256)
        assertTrue("fixture must need multiple chunks", chunks.size > 1)

        val decoder = QrChunkCodec.Decoder()
        var outcome: QrChunkCodec.Decoder.Outcome? = null
        chunks.forEach { outcome = decoder.accept(it) }
        assertTrue("in-order scan must complete, got $outcome",
            outcome is QrChunkCodec.Decoder.Outcome.Complete)

        val recovered = QrEnvelopeCodec.decodeKubeconfig(
            (outcome as QrChunkCodec.Decoder.Outcome.Complete).jsonPayload)
        assertNotNull("envelope must decode to the stripped kubeconfig", recovered)
        assertEquals(stripped.yaml, recovered)

        val parsed = KubeconfigParser().parse(recovered!!)
        assertTrue("scanned result must import, got $parsed", parsed is ParseResult.Valid)
    }

    @Test fun mainChunksModeRoundTripsThroughDecoder() {
        // Exercise the actual CLI path (--chunks), verifying stdout payloads.
        val out = java.io.ByteArrayOutputStream()
        val saved = System.out
        System.setOut(java.io.PrintStream(out))
        val code = try {
            run(arrayOf("-", "--chunks", "--max-chars", "256")) { fixture }
        } finally {
            System.setOut(saved)
        }
        assertEquals(0, code)
        val chunks = out.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
        val decoder = QrChunkCodec.Decoder()
        var outcome: QrChunkCodec.Decoder.Outcome? = null
        chunks.forEach { outcome = decoder.accept(it) }
        assertTrue("CLI --chunks must yield a complete sequence, got $outcome",
            outcome is QrChunkCodec.Decoder.Outcome.Complete)
    }

    @Test fun mainRejectsUnimportableInput() {
        val code = run(arrayOf("-")) { "not: a: kubeconfig: [" }
        assertEquals("garbage input must exit 2", 2, code)
    }
}
