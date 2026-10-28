package dev.upihub

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Drives the real activities on Robolectric: the chooser, default handling,
 * the intent handed to the paying app, and the `upi://` link relay. It also
 * writes screenshots to build/screenshots for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppWorkflowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val merchantQr = "upi://pay?pa=chaipoint.blr@okaxis&pn=Chai%20Point&mc=5812&tr=TR42&am=149.00&cu=INR&sign=AbC%2B%3D"

    @Before fun installFakeUpiApps() {
        // A subset is "installed" so the chooser shows both states.
        installUpiApp("in.gokiwi.kiwitpap", "Kiwi", Color.rgb(0x12, 0xB7, 0x6A))
        installUpiApp("net.one97.paytm", "Paytm", Color.rgb(0x00, 0x2E, 0x6E))
        installUpiApp("com.dreamplug.androidapp", "CRED", Color.rgb(0x22, 0x22, 0x22))
        installUpiApp("com.google.android.apps.nbu.paisa.user", "Google Pay", Color.rgb(0x42, 0x85, 0xF4))
        installUpiApp("com.phonepe.app", "PhonePe", Color.rgb(0x5F, 0x25, 0x9F))
    }

    @Test fun homeAsksForCameraOnFirstLaunch() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val requested = shadowOf(activity).lastRequestedPermission
        assertNotNull(requested)
        assertTrue(Manifest.permission.CAMERA in requested.requestedPermissions)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.permissionPanel).visibility)
        assertEquals("Allow camera", activity.findViewById<Button>(R.id.allowCameraButton).text.toString())
        capture(activity.window.decorView, "01-home-camera-permission")
    }

    @Test fun cameraGrantedAfterPromptStartsScanning() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val request = shadowOf(activity).lastRequestedPermission
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.permissionPanel).visibility)
    }

    @Test fun homeScansImmediatelyWhenCameraGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.permissionPanel).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.overlay).visibility)
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        capture(activity.window.decorView, "02-home-scanning")
    }

    @Test fun chooserPreselectsKiwiAndPaysWithUnchangedQr() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()

        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val list = sheet.findViewById<RecyclerView>(R.id.appList)!!
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        assertEquals("Continue with Kiwi", pay.text.toString())
        assertEquals(UpiApps.PREFERRED.size, list.adapter!!.itemCount)
        capture(activity.window.decorView, "03-chooser", dialog = sheet.window!!.decorView)

        pay.performClick()
        idle()
        val sent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals("in.gokiwi.kiwitpap", sent.`package`)
        assertEquals(merchantQr, sent.dataString)
    }

    @Test fun pickingAnotherAppPaysWithIt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val list = sheet.findViewById<RecyclerView>(R.id.appList)!!
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        list.layout(0, 0, 1080, list.measuredHeight)
        val paytmIndex = UpiApps.discover(activity, Prefs(activity).defaultPackage, false)
            .indexOfFirst { it.packageName == "net.one97.paytm" }
        list.findViewHolderForAdapterPosition(paytmIndex)!!.itemView.performClick()
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        assertEquals("Continue with Paytm", pay.text.toString())
        pay.performClick()
        idle()
        assertEquals("net.one97.paytm", shadowOf(activity).nextStartedActivityForResult.intent.`package`)
    }

    @Test fun defaultOpensDirectlyWhenAskEveryTimeIsOff() {
        Prefs(app).alwaysAsk = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals("in.gokiwi.kiwitpap", sent.`package`)
        assertEquals(merchantQr, sent.dataString)
    }

    @Test fun upiLinkFromAnotherAppIsRoutedAndResultRelayed() {
        Prefs(app).alwaysAsk = false
        val controller = Robolectric.buildActivity(
            RouterActivity::class.java,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(merchantQr)),
        ).setup()
        idle()
        val router = controller.get()
        val forwarded = shadowOf(router).nextStartedActivityForResult
        assertEquals("in.gokiwi.kiwitpap", forwarded.intent.`package`)
        val response = Intent().putExtra("response", "txnId=X1&responseCode=00&Status=SUCCESS")
        shadowOf(router).receiveResult(forwarded.intent, android.app.Activity.RESULT_OK, response)
        idle()
        assertTrue(router.isFinishing)
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(router).resultCode)
        assertEquals("txnId=X1&responseCode=00&Status=SUCCESS", shadowOf(router).resultIntent.getStringExtra("response"))
    }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun chooserDarkTheme() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        capture(activity.window.decorView, "04-chooser-dark", dialog = sheet.window!!.decorView)
    }

    /** Renders the adaptive icon's visible area as the 512x512 Play Store icon. */
    @Test fun exportPlayStoreIcon() {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // The adaptive icon is 108dp with a 72dp visible area; scale so 72dp fills 512px.
        val full = size * 108 / 72
        val offset = (size - full) / 2
        for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            ContextCompat.getDrawable(app, id)!!.apply { setBounds(offset, offset, offset + full, offset + full) }.draw(canvas)
        }
        save(bmp, "play-store-icon-512")
    }

    // ---- helpers ----

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun installUpiApp(pkg: String, label: String, color: Int) {
        val spm = shadowOf(app.packageManager)
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        })
        val component = ComponentName(pkg, "$pkg.PayActivity")
        spm.addActivityIfNotPresent(component)
        spm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("upi")
        })
        spm.setApplicationIcon(pkg, letterIcon(app, label.first(), color))
    }

    private fun letterIcon(context: Context, letter: Char, color: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(72f, 72f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; textSize = 72f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        c.drawText(letter.toString(), 72f, 72f - (text.descent() + text.ascent()) / 2, text)
        return BitmapDrawable(context.resources, bmp)
    }

    /**
     * Robolectric has no camera: hide the preview and paint a QR on a desk-like
     * background in its place so screenshots resemble a live scan.
     */
    private fun fakeCameraFrame(preview: View) {
        preview.visibility = View.INVISIBLE
        val area = preview.parent as View
        area.measure(0, 0)
        val w = maxOf(area.width, 1233)
        val h = maxOf(area.height, 2200)
        val m = QRCodeWriter().encode(merchantQr, BarcodeFormat.QR_CODE, 0, 0)
        val qr = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) qr.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x6B, 0x5A, 0x4A))
        val side = w * 0.5f
        val left = (w - side) / 2
        val top = (h - side) / 2
        c.drawRect(left - 40, top - 40, left + side + 40, top + side + 40, Paint().apply { color = Color.WHITE })
        c.drawBitmap(qr, null, android.graphics.RectF(left, top, left + side, top + side), Paint())
        area.background = BitmapDrawable(area.resources, bmp)
    }

    private fun capture(root: View, name: String, dialog: View? = null) {
        idle()
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        root.draw(canvas)
        if (dialog != null) {
            canvas.drawColor(Color.argb(0x66, 0, 0, 0))
            dialog.draw(canvas)
        }
        save(bmp, name)
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
