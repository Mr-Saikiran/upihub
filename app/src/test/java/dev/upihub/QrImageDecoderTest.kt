package dev.upihub

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImageDecoderTest {
    private fun render(text: String, inverted: Boolean = false): Triple<IntArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val dark = if (inverted) -1 else 0xFF000000.toInt()
        val light = if (inverted) 0xFF000000.toInt() else -1
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) dark else light }
        return Triple(px, m.width, m.height)
    }

    @Test fun decodesUpiQrFromPixels() {
        val raw = "upi://pay?pa=shop@okaxis&pn=Chai%20Point&am=49.50&cu=INR"
        val (px, w, h) = render(raw)
        assertEquals(raw, QrImageDecoder.decodePixels(px, w, h))
    }

    @Test fun decodesInvertedQr() {
        val (px, w, h) = render("upi://pay?pa=a@ybl", inverted = true)
        assertEquals("upi://pay?pa=a@ybl", QrImageDecoder.decodePixels(px, w, h))
    }

    @Test fun blankImageHasNoCode() {
        assertNull(QrImageDecoder.decodePixels(IntArray(100 * 100) { -1 }, 100, 100))
    }
}
