package dev.upihub

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/** Reads a QR code from a picture, e.g. a screenshot of a merchant's UPI QR. */
object QrImageDecoder {
    /** Large enough for phone screenshots; bounds the decoded bitmap's memory. */
    private const val MAX_SIDE = 2048

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** Returns the QR text, or null when the image has no readable QR code. */
    fun decode(resolver: ContentResolver, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return decodePixels(pixels, width, height)
    }

    /** ARGB pixels in, QR text out. Tries both binarizers and inverted (light-on-dark) codes. */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        val source: LuminanceSource = RGBLuminanceSource(width, height, pixels)
        for (candidate in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(candidate)), BinaryBitmap(GlobalHistogramBinarizer(candidate)))) {
                try {
                    return MultiFormatReader().decode(bitmap, HINTS).text
                } catch (_: NotFoundException) {
                    // Try the next strategy.
                } catch (_: com.google.zxing.ReaderException) {
                    // Checksum/format errors: try the next strategy.
                }
            }
        }
        return null
    }
}
