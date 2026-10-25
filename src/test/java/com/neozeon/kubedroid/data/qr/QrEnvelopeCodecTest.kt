package com.neozeon.kubedroid.data.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 03 Testing: QR envelope codec round trip + rejection of foreign JSON. */
class QrEnvelopeCodecTest {

    private val kubeconfig = """
        apiVersion: v1
        kind: Config
        clusters: []
    """.trimIndent()

    @Test fun roundTrip() {
        val envelope = QrEnvelopeCodec.encode(kubeconfig)
        assertEquals(kubeconfig, QrEnvelopeCodec.decodeKubeconfig(envelope))
    }

    @Test fun envelopeCarriesVersion() {
        val envelope = QrEnvelopeCodec.encode(kubeconfig)
        assertEquals(
            QrEnvelope(version = 1, kubeconfig = kubeconfig),
            kotlinx.serialization.json.Json.decodeFromString(QrEnvelope.serializer(), envelope),
        )
    }

    @Test fun foreignJsonRejected() {
        assertNull(QrEnvelopeCodec.decodeKubeconfig("""{"foo":"bar"}"""))
        assertNull(QrEnvelopeCodec.decodeKubeconfig("not json at all"))
        assertNull(QrEnvelopeCodec.decodeKubeconfig("""{"version":1,"kubeconfig":"  "}"""))
    }

    @Test fun fullChunkedRoundTripThroughCodec() {
        // mirrors what tools/qr-export will do: envelope -> chunks -> decoder
        val big = "apiVersion: v1\n" + "x".repeat(4000)
        val chunks = QrChunkCodec.encode(QrEnvelopeCodec.encode(big))
        val decoder = QrChunkCodec.Decoder()
        var outcome: QrChunkCodec.Decoder.Outcome? = null
        for (chunk in chunks) {
            outcome = decoder.accept(chunk)
        }
        check(outcome is QrChunkCodec.Decoder.Outcome.Complete)
        assertEquals(big, QrEnvelopeCodec.decodeKubeconfig(outcome.jsonPayload))
    }
}
