package com.neozeon.kubedroid.qrexport

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** zxing-backed QR rendering: unicode half-blocks for terminals, PNG files. */
object QrRenderer {

    private fun matrix(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to 4,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /** Two modules per text row via ▀/▄/█ so QRs stay scannable on screen. */
    fun terminal(text: String): String {
        val m = matrix(text)
        val sb = StringBuilder()
        var y = 0
        while (y < m.height) {
            for (x in 0 until m.width) {
                val top = m.get(x, y)
                val bottom = y + 1 < m.height && m.get(x, y + 1)
                sb.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString().trimEnd('\n')
    }

    /** PNG module size: zxing's own detector (and most phone scanners) fail at 1px/module. */
    private const val PNG_SCALE = 4

    fun png(text: String, file: File) {
        val m = matrix(text)
        val w = m.width * PNG_SCALE
        val h = m.height * PNG_SCALE
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) {
            for (y in 0 until h) {
                img.setRGB(x, y, if (m.get(x / PNG_SCALE, y / PNG_SCALE)) 0x000000 else 0xFFFFFF)
            }
        }
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }

    /** Decode a rendered matrix back to text — used by tests to close the loop. */
    fun readBack(text: String): String {
        val m = matrix(text)
        val scale = PNG_SCALE
        val w = m.width * scale
        val h = m.height * scale
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (m.get(x / scale, y / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(w, h, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
