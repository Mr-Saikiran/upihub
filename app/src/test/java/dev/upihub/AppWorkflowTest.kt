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
import org.junit.Assert.assertFalse
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

    /**
     * FileProvider caches its roots per authority in a static map. Robolectric
     * reuses the class across tests but gives each test a new data directory,
     * so a stale cache would point at an earlier test's folder.
     */
    @Before fun resetFileProviderCache() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Before fun installFakeUpiApps() {
        // Most tests start after the user picked Kiwi; first-launch tests clear it.
        Prefs(app).defaultPackage = "in.gokiwi.kiwitpap"
        // A subset is installed: INDmoney, MobiKwik, Jupiter and BHIM are not.
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
        // Only installed apps are listed: no BHIM, INDmoney, MobiKwik or Jupiter.
        assertEquals(listOf("Kiwi", "Paytm", "CRED", "Google Pay", "PhonePe"), chooserLabels(sheet))
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
        val paytmIndex = chooserLabels(sheet).indexOf("Paytm")
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

    @Test fun personalQrWithoutAmountAsksForItAndSendsIt() {
        val personalQr = "upi://pay?pa=ravi.k@okaxis&pn=Ravi%20Kumar&mode=02"
        Prefs(app).alwaysAsk = false // The amount step is still required.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        activity.startPayment((UpiPayment.parse(personalQr) as UpiPayment.Parsed.Ok).payment)
        idle()

        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.amountLayout)!!.visibility)
        assertFalse(pay.isEnabled)
        sheet.findViewById<android.widget.EditText>(R.id.amountInput)!!.setText("150")
        assertTrue(pay.isEnabled)
        assertEquals("Pay ₹150.00 with Kiwi", pay.text.toString())
        capture(activity.window.decorView, "05-chooser-enter-amount", dialog = sheet.window!!.decorView)

        pay.performClick()
        idle()
        val sent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals("in.gokiwi.kiwitpap", sent.`package`)
        assertEquals("$personalQr&cu=INR&am=150.00", sent.dataString)
    }

    @Test fun payButtonStaysOnScreenWithManyApps() {
        installUpiApp("in.indwealth", "INDmoney", Color.BLACK)
        installUpiApp("com.mobikwik_new", "MobiKwik", Color.rgb(0x10, 0x5B, 0xE8))
        installUpiApp("money.jupiter", "Jupiter", Color.rgb(0xF2, 0x6B, 0x5B))
        for (n in 1..3) {
            installUpiApp("com.example.otherupi$n", "Other UPI $n", Color.rgb(0xF0, 0xA0, 0x40))
            Prefs(app).setVisible("com.example.otherupi$n", true)
        }
        val personalQr = "upi://pay?pa=ravi.k@okaxis&pn=Ravi%20Kumar"
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(personalQr) as UpiPayment.Parsed.Ok).payment)
        idle()

        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val list = sheet.findViewById<RecyclerView>(R.id.appList)!!
        assertEquals(11, list.adapter!!.itemCount)
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        val window = sheet.window!!.decorView
        val loc = IntArray(2)
        pay.getLocationInWindow(loc)
        assertTrue("button bottom ${loc[1] + pay.height} > window ${window.height}", loc[1] + pay.height <= window.height)
        assertTrue("button not laid out", pay.height > 0)
        capture(activity.window.decorView, "06-chooser-many-apps", dialog = window)
    }

    @Test fun hidingAnAppInManagerRemovesItFromPaymentList() {
        val manager = Robolectric.buildActivity(ManageAppsActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("Kiwi", "Paytm", "CRED", "Google Pay", "PhonePe"), managerLabels(manager))
        managerSwitch(manager, "Paytm").performClick()
        idle()
        assertEquals("Hidden", managerRow(manager, "Paytm").findViewById<android.widget.TextView>(R.id.status).text.toString())

        assertEquals(listOf("Kiwi", "CRED", "Google Pay", "PhonePe"), openChooserLabels())
    }

    @Test fun otherUpiAppsAreShownUntilRemoved() {
        installUpiApp("com.example.otherupi", "Other UPI", Color.rgb(0xF0, 0xA0, 0x40))
        assertTrue("Other UPI" in openChooserLabels())

        val manager = Robolectric.buildActivity(ManageAppsActivity::class.java).setup().get()
        idle()
        assertTrue(managerSwitch(manager, "Other UPI").isChecked)
        managerSwitch(manager, "Other UPI").performClick()
        idle()
        assertTrue("Other UPI" !in openChooserLabels())
    }

    @Test fun firstLaunchAsksForDefaultFromAllUpiApps() {
        Prefs(app).defaultPackage = null
        installUpiApp("com.example.otherupi", "Other UPI", Color.rgb(0xF0, 0xA0, 0x40))
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        val picker = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = picker.listView
        val labels = (0 until list.adapter.count).map { (list.adapter.getItem(it) as UpiApp).label }
        assertEquals(listOf("Kiwi", "Paytm", "CRED", "Google Pay", "PhonePe", "Other UPI"), labels)
        assertEquals("Choose default app", activity.findViewById<android.widget.TextView>(R.id.defaultAppText).text.toString())
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        capture(activity.window.decorView, "09-choose-default", dialog = picker.window!!.decorView)

        shadowOf(list).performItemClick(labels.indexOf("Paytm"))
        idle()
        assertEquals("net.one97.paytm", Prefs(app).defaultPackage)
        assertEquals("Paytm", activity.findViewById<android.widget.TextView>(R.id.defaultAppText).text.toString())
    }

    @Test fun withoutDefaultFirstPickedAppBecomesDefault() {
        Prefs(app).defaultPackage = null
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        (ShadowDialog.getLatestDialog())?.dismiss() // Skip the launch picker.
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertTrue(sheet.findViewById<android.widget.CheckBox>(R.id.rememberCheck)!!.isChecked)
        sheet.findViewById<Button>(R.id.payButton)!!.performClick()
        idle()
        assertEquals("in.gokiwi.kiwitpap", Prefs(app).defaultPackage)
    }

    @Test fun appWhoseUpiActivityLacksDefaultCategoryIsListedAndOpenedDirectly() {
        installUpiApp("money.super.payments", "super.money", Color.rgb(0xFF, 0xD5, 0x00), defaultCategory = false)
        assertTrue("super.money" in openChooserLabels())

        Prefs(app).defaultPackage = "money.super.payments"
        Prefs(app).alwaysAsk = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(ComponentName("money.super.payments", "money.super.payments.PayActivity"), sent.component)
        assertEquals(merchantQr, sent.dataString)
    }

    @Test fun supportedAppWithoutUpiLinksIsExplainedNotOffered() {
        // BHIM installed, but with no activity that takes upi:// links.
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "in.org.npci.upiapp"
            applicationInfo = ApplicationInfo().apply { packageName = "in.org.npci.upiapp"; nonLocalizedLabel = "BHIM" }
        })
        assertTrue("BHIM" !in openChooserLabels())
        val manager = Robolectric.buildActivity(ManageAppsActivity::class.java).setup().get()
        idle()
        assertEquals("Installed, but doesn't accept UPI links from other apps",
            managerRow(manager, "BHIM").findViewById<android.widget.TextView>(R.id.status).text.toString())
        assertEquals(View.INVISIBLE, managerSwitch(manager, "BHIM").visibility)
    }

    @Test fun defaultAppCannotBeHidden() {
        val manager = Robolectric.buildActivity(ManageAppsActivity::class.java).setup().get()
        idle()
        assertEquals(View.INVISIBLE, managerSwitch(manager, "Kiwi").visibility)
        assertEquals("Default · always shown",
            managerRow(manager, "Kiwi").findViewById<android.widget.TextView>(R.id.status).text.toString())
    }

    @Test fun sortAndDragReorderThePaymentList() {
        val manager = Robolectric.buildActivity(ManageAppsActivity::class.java).setup().get()
        idle()
        manager.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.sortAz, 0)
        idle()
        val sorted = listOf("CRED", "Google Pay", "Kiwi", "Paytm", "PhonePe")
        assertEquals(sorted, managerLabels(manager))
        assertEquals(sorted, openChooserLabels())

        // Drag PhonePe to the top.
        manager.move(4, 0)
        manager.saveOrder()
        assertEquals(listOf("PhonePe", "CRED", "Google Pay", "Kiwi", "Paytm"), openChooserLabels())
        capture(manager.window.decorView, "08-manage-apps")

        // Reset restores the default order.
        manager.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.resetList, 0)
        idle()
        assertEquals(listOf("Kiwi", "Paytm", "CRED", "Google Pay", "PhonePe"), openChooserLabels())
    }

    @Test fun appThatTakesImagesGetsTheUnchangedQrAsAnImage() {
        addShareTarget("com.phonepe.app")
        Prefs(app).defaultPackage = "com.phonepe.app"
        val staticQr = "upi://pay?pa=vyapar.1787@hdfcbank&pn=Ram%20Reddy&mc=5411&mode=01&orgid=180001&sign=MEUCIQ%2BabcD%3D"
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fakeCameraFrame(activity.findViewById(R.id.barcodeView))
        activity.startPayment((UpiPayment.parse(staticQr) as UpiPayment.Parsed.Ok).payment)
        idle()

        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        // No amount step: PhonePe reads the QR and asks for the amount itself.
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.amountLayout)!!.visibility)
        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.deliveryNote)!!.visibility)
        assertTrue(pay.isEnabled)
        assertEquals("Send QR to PhonePe", pay.text.toString())
        capture(activity.window.decorView, "10-chooser-send-qr", dialog = sheet.window!!.decorView)

        pay.performClick()
        idle()
        val sent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals(ComponentName("com.phonepe.app", "com.phonepe.app.ShareActivity"), sent.component)
        assertEquals("image/png", sent.type)
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = sent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("techswitch.upihub.qr", uri.authority)
        assertEquals(staticQr, decodeSharedImage(uri))
        assertTrue(activity.findViewById<android.widget.TextView>(R.id.resultText).text.contains("Sent the QR to PhonePe"))
    }

    @Test fun appWithoutImageTargetStillGetsTheLink() {
        addShareTarget("com.phonepe.app") // PhonePe takes images, Kiwi (the default) does not.
        Prefs(app).alwaysAsk = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals("in.gokiwi.kiwitpap", sent.`package`)
        assertEquals(merchantQr, sent.dataString)
    }

    @Test fun signedQrWithoutAmountIsSentUnchangedAsALink() {
        val signedQr = "upi://pay?pa=vyapar.1787@hdfcbank&pn=Ram%20Reddy&mc=5411&mode=02&sign=MEUCIQ%2BabcD%3D"
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(signedQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.amountLayout)!!.visibility)
        assertTrue(sheet.findViewById<android.widget.TextView>(R.id.deliveryNote)!!.text.contains("signed"))
        val pay = sheet.findViewById<Button>(R.id.payButton)!!
        assertTrue(pay.isEnabled)
        pay.performClick()
        idle()
        assertEquals(signedQr, shadowOf(activity).nextStartedActivityForResult.intent.dataString)
    }

    @Test fun sharingCanBeSwitchedOff() {
        addShareTarget("com.phonepe.app")
        Prefs(app).defaultPackage = "com.phonepe.app"
        Prefs(app).shareQrImage = false
        Prefs(app).alwaysAsk = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivityForResult.intent.action)
    }

    @Test fun linksRelayedFromOtherAppsStayLinksSoTheirResultReturns() {
        addShareTarget("com.phonepe.app")
        Prefs(app).defaultPackage = "com.phonepe.app"
        Prefs(app).alwaysAsk = false
        val router = Robolectric.buildActivity(RouterActivity::class.java,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(merchantQr))).setup().get()
        idle()
        val forwarded = shadowOf(router).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, forwarded.action)
        assertEquals("com.phonepe.app", forwarded.`package`)
    }

    @Test fun declinedLinkCanBeRetriedAsTheQrImage() {
        addShareTarget("com.phonepe.app")
        Prefs(app).defaultPackage = "com.phonepe.app"
        Prefs(app).shareQrImage = false // First attempt goes as a link, as before this change.
        Prefs(app).alwaysAsk = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val link = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(link.intent, android.app.Activity.RESULT_OK,
            Intent().putExtra("response", "txnId=X&responseCode=ZM&Status=FAILURE"))
        idle()

        val declined = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val retry = declined.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals("Send the QR to PhonePe instead", retry.text.toString())
        capture(activity.window.decorView, "11-declined-retry", dialog = declined.window!!.decorView)
        retry.performClick()
        idle()
        val shared = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, shared.action)
        @Suppress("DEPRECATION")
        assertEquals(merchantQr, decodeSharedImage(shared.getParcelableExtra(Intent.EXTRA_STREAM)!!))
    }

    @Test fun homeScreenWidgetOpensScanner() {
        val manager = android.appwidget.AppWidgetManager.getInstance(app)
        val id = shadowOf(manager).createWidget(ScanWidgetProvider::class.java, R.layout.widget_scan)
        val view = shadowOf(manager).getViewFor(id)
        view.findViewById<View>(R.id.widgetRoot).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component!!.className)

        val px = (96 * app.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        save(bmp, "07-widget")
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

    /** 1024x500 Play Store feature graphic built from the launcher icon. */
    @Test fun exportPlayStoreFeatureGraphic() {
        val w = 1024
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(0x7B, 0x5C, 0xFF), Color.rgb(0x2A, 0x14, 0x8C), android.graphics.Shader.TileMode.CLAMP)
        })
        val iconSize = 300
        val left = 70
        val top = (h - iconSize) / 2
        ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground)!!.apply {
            val full = iconSize * 108 / 72
            val off = (iconSize - full) / 2
            setBounds(left + off, top + off, left + off + full, top + off + full)
        }.draw(c)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 76f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF); textSize = 36f }
        val x = left + iconSize + 50f
        c.drawText("UPI Hub", x, 215f, title)
        c.drawText("One scanner for all your", x, 285f, body)
        c.drawText("UPI apps. Pick, pay, done.", x, 335f, body)
        save(bmp, "play-feature-graphic-1024x500")
    }

    // ---- helpers ----

    private fun chooserLabels(sheet: BottomSheetDialog): List<String> {
        val list = sheet.findViewById<RecyclerView>(R.id.appList)!!
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        list.layout(0, 0, 1080, list.measuredHeight)
        return (0 until list.adapter!!.itemCount).map {
            list.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<android.widget.TextView>(R.id.label).text.toString()
        }
    }

    /** Opens the chooser for a merchant QR on a fresh home screen and returns its app labels. */
    private fun openChooserLabels(): List<String> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.startPayment((UpiPayment.parse(merchantQr) as UpiPayment.Parsed.Ok).payment)
        idle()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        return chooserLabels(sheet).also { sheet.dismiss(); idle() }
    }

    private fun managerList(manager: ManageAppsActivity) = manager.findViewById<RecyclerView>(R.id.appList).also {
        it.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        it.layout(0, 0, 1080, it.measuredHeight)
    }

    private fun managerLabels(manager: ManageAppsActivity): List<String> {
        val list = managerList(manager)
        return (0 until list.adapter!!.itemCount).map {
            list.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<android.widget.TextView>(R.id.label).text.toString()
        }
    }

    private fun managerRow(manager: ManageAppsActivity, label: String): View {
        val list = managerList(manager)
        return list.findViewHolderForAdapterPosition(managerLabels(manager).indexOf(label))!!.itemView
    }

    private fun managerSwitch(manager: ManageAppsActivity, label: String) =
        managerRow(manager, label).findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.visibleSwitch)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Adds an exported image share target, like Google Pay's and PhonePe's. */
    private fun addShareTarget(pkg: String) {
        val spm = shadowOf(app.packageManager)
        val component = ComponentName(pkg, "$pkg.ShareActivity")
        spm.addActivityIfNotPresent(component).exported = true
        spm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEND).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
        })
    }

    private fun decodeSharedImage(uri: android.net.Uri): String? {
        val bmp = app.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return QrImageDecoder.decodePixels(px, bmp.width, bmp.height)
    }

    private fun installUpiApp(pkg: String, label: String, color: Int, defaultCategory: Boolean = true) {
        val spm = shadowOf(app.packageManager)
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        })
        val component = ComponentName(pkg, "$pkg.PayActivity")
        spm.addActivityIfNotPresent(component).exported = true
        spm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            if (defaultCategory) addCategory(Intent.CATEGORY_DEFAULT)
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
