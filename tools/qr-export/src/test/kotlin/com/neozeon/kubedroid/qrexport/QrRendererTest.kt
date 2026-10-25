package com.neozeon.kubedroid.qrexport

import com.neozeon.kubedroid.data.qr.QrChunkCodec
import com.neozeon.kubedroid.data.qr.QrEnvelopeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.imageio.ImageIO

/** Stage 7: every emitted chunk must actually render to a scannable QR. */
class QrRendererTest {

    @get:Rule val tmp = TemporaryFolder()

    private val chunk = QrChunkCodec.encode(
        QrEnvelopeCodec.encode("apiVersion: v1\nkind: Config\n" + "y".repeat(900)),
        maxChars = 512,
    ).first()

    @Test fun matrixDecodesBackToChunkText() {
        assertEquals(chunk, QrRenderer.readBack(chunk))
    }

    @Test fun terminalBlockUsesHalfBlocksAndIsNonEmpty() {
        val art = QrRenderer.terminal(chunk)
        assertTrue(art.isNotEmpty())
        assertTrue("must use block glyphs", art.any { it in "▀▄█" })
        val widths = art.lines().map { it.length }.distinct()
        assertEquals("all rows same width", 1, widths.size)
    }

    @Test fun pngFileIsWrittenAndReadable() {
        val file = tmp.newFile("chunk-01.png")
        QrRenderer.png(chunk, file)
        assertTrue(file.length() > 0)
        val img = ImageIO.read(file)
        assertTrue("QR must be roughly square", img.width in img.height - 8..img.height + 8)
    }
}
