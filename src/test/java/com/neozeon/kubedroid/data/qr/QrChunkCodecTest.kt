package com.neozeon.kubedroid.data.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 03 Testing: QrChunkCodec round trips, ordering, corruption. */
class QrChunkCodecTest {

    private val payload = """{"version":1,"kubeconfig":"${"apiVersion: v1\nkind: Config\n" + "x".repeat(4000)}"}"""

    @Test fun roundTripMultiChunk() {
        val chunks = QrChunkCodec.encode(payload, maxChars = 256)
        assertTrue("expected multiple chunks", chunks.size > 1)
        chunks.forEach { assertTrue(it.startsWith(QrChunkCodec.PREFIX)) }

        val decoder = QrChunkCodec.Decoder()
        var outcome: QrChunkCodec.Decoder.Outcome = QrChunkCodec.Decoder.Outcome.Awaiting(1, null)
        for ((i, chunk) in chunks.withIndex()) {
            outcome = decoder.accept(chunk)
            if (i == chunks.lastIndex) {
                assertTrue("last chunk must complete, got $outcome",
                    outcome is QrChunkCodec.Decoder.Outcome.Complete)
            } else {
                assertTrue("mid chunk must await, got $outcome",
                    outcome is QrChunkCodec.Decoder.Outcome.Awaiting)
                assertEquals(i + 2, (outcome as QrChunkCodec.Decoder.Outcome.Awaiting).nextIndex)
            }
        }
        assertEquals(payload, (outcome as QrChunkCodec.Decoder.Outcome.Complete).jsonPayload)
    }

    @Test fun singleChunkConfig() {
        val small = """{"a":1}"""
        val chunks = QrChunkCodec.encode(small)
        assertEquals(1, chunks.size)
        val outcome = QrChunkCodec.Decoder().accept(chunks.single())
        assertTrue(outcome is QrChunkCodec.Decoder.Outcome.Complete)
        assertEquals(small, (outcome as QrChunkCodec.Decoder.Outcome.Complete).jsonPayload)
    }

    @Test fun outOfOrderRejected() {
        val chunks = QrChunkCodec.encode(payload, maxChars = 256)
        assertTrue(chunks.size > 2)
        val outcome = QrChunkCodec.Decoder().accept(chunks[1])
        assertTrue("expected out-of-order failure, got $outcome",
            outcome is QrChunkCodec.Decoder.Outcome.Failed)
        assertTrue((outcome as QrChunkCodec.Decoder.Outcome.Failed).reason.contains("out of order"))
    }

    @Test fun corruptBase64Rejected() {
        val chunks = QrChunkCodec.encode(payload, maxChars = 256)
        val corrupt = chunks.first().replaceAfterLast(':', "!!!not base64!!!")
        val outcome = QrChunkCodec.Decoder().accept(corrupt)
        assertTrue(outcome is QrChunkCodec.Decoder.Outcome.Failed)
    }

    @Test fun totalMismatchRejected() {
        val chunks = QrChunkCodec.encode(payload, maxChars = 256)
        val decoder = QrChunkCodec.Decoder()
        decoder.accept(chunks.first())
        // rewrite chunk 2 to claim a different total than chunk 1 declared
        val tampered = QrChunkCodec.PREFIX + "2/99:" + chunks[1].substringAfter(':')
        val outcome = decoder.accept(tampered)
        assertTrue(outcome is QrChunkCodec.Decoder.Outcome.Failed)
        assertTrue((outcome as QrChunkCodec.Decoder.Outcome.Failed).reason.contains("disagrees"))
    }

    @Test fun foreignPrefixRejected() {
        val outcome = QrChunkCodec.Decoder().accept("someother:1/1:aGVsbG8=")
        assertTrue(outcome is QrChunkCodec.Decoder.Outcome.Failed)
        assertTrue((outcome as QrChunkCodec.Decoder.Outcome.Failed).reason.contains("prefix"))
    }

    @Test fun nonJsonPayloadFailsAtCompletion() {
        val notJson = "plain text, not json: ${"y".repeat(2000)}"
        val chunks = QrChunkCodec.encode(notJson, maxChars = 256)
        val decoder = QrChunkCodec.Decoder()
        var outcome: QrChunkCodec.Decoder.Outcome? = null
        chunks.forEach { outcome = decoder.accept(it) }
        assertTrue("non-JSON must fail on completion, got $outcome",
            outcome is QrChunkCodec.Decoder.Outcome.Failed)
        assertTrue((outcome as QrChunkCodec.Decoder.Outcome.Failed).reason.contains("JSON"))
    }
}
