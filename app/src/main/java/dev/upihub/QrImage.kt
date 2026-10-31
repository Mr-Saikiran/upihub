package dev.upihub

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/**
 * The QR for "QR Share and Pay": the original QR text, unchanged, drawn as an
 * image the paying app decodes itself. Nothing is re-encoded into the text,
 * so merchant fields and a `sign` signature arrive exactly as printed.
 */
object QrImage {
    private const val SIZE = 1024
    private const val DIR = "qr"

    /** ARGB pixels of the QR for [text], with a white quiet zone. */
    fun pixels(text: String, size: Int = SIZE): IntArray {
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        // ASCII needs no charset: setting one adds an ECI header some scanners mishandle.
        if (text.any { it.code > 0x7f }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
    }

    fun bitmap(text: String, size: Int = SIZE): Bitmap =
        Bitmap.createBitmap(pixels(text, size), size, size, Bitmap.Config.ARGB_8888)

    /** Writes the QR to the app's cache and returns a content:// URI other apps can be granted. */
    fun share(context: Context, text: String): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // Only the latest QR is ever needed.
        val file = File(dir, "upi-qr-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap(text).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.qr", file)
    }

    /**
     * Saves the QR to Pictures/UPI Hub so a payment app's "scan from gallery"
     * can read it. Android 10+ only (no storage permission needed); returns
     * null on older versions or failure.
     */
    fun saveToPhotos(context: Context, text: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "upi-qr-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/UPI Hub")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap(text).compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return null
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
