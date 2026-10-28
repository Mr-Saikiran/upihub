package dev.upihub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.upihub.databinding.ActivityMainBinding
import dev.upihub.databinding.DialogManualBinding
import dev.upihub.databinding.SheetSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens straight into a live QR scanner. A UPI code found by the camera, a
 * picked or shared screenshot, or a typed UPI ID all go through [PaymentFlow].
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var flow: PaymentFlow

    /** True while a chooser or payment app is handling a code; camera results are ignored. */
    private var busy = false

    /** The last code handled, ignored for a moment so it does not reopen the chooser at once. */
    private var lastCode: String? = null
    private var ignoreLastUntil = 0L
    private var torchOn = false

    private val payLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        showResult(UpiResponse.parse(r.data?.getStringExtra("response")))
        finishHandling(holdMillis = 5_000)
    }

    // System photo picker: no storage permission needed.
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::handleImage)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Android stops showing the prompt after repeated denials; only Settings can grant it then.
        prefs.cameraBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        updateCameraState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        flow = PaymentFlow(this, prefs, launch = { payLauncher.launch(it) }, onCancel = { finishHandling(holdMillis = 2_500) })

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.topBar.updatePadding(top = bars.top + dp(12))
            b.panel.updatePadding(bottom = bars.bottom + dp(20))
            insets
        }

        b.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        b.barcodeView.decodeContinuous(BarcodeCallback { result -> result.text?.let(::onCameraCode) })

        b.settingsButton.setOnClickListener { showSettings() }
        b.defaultAppCard.setOnClickListener { chooseDefault() }
        b.manualButton.setOnClickListener { showManualEntry() }
        b.galleryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.torchButton.visibility =
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) View.VISIBLE else View.GONE
        b.torchButton.setOnClickListener { setTorch(!torchOn) }
        b.allowCameraButton.setOnClickListener { requestCamera(fromButton = true) }

        if (savedInstanceState == null) {
            sharedImage(intent)?.let(::handleImage)
            // Open straight into the scanner: ask for the camera on launch.
            if (!hasCamera()) requestCamera(fromButton = false)
            // No default yet (or it was uninstalled): let the user pick one from all UPI apps.
            if (currentDefault() == null) chooseDefault()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let(::handleImage)
    }

    override fun onResume() {
        super.onResume()
        refreshDefaultApp()
        updateCameraState()
    }

    override fun onPause() {
        super.onPause()
        b.barcodeView.pause()
        setTorch(false)
    }

    // ---- Camera ----

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCamera(fromButton: Boolean) {
        if (fromButton && prefs.cameraBlocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun updateCameraState() {
        val granted = hasCamera()
        b.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        b.overlay.visibility = if (granted) View.VISIBLE else View.GONE
        b.hintText.visibility = if (granted) View.VISIBLE else View.GONE
        b.torchButton.isEnabled = granted
        if (granted) {
            b.barcodeView.resume()
        } else {
            b.barcodeView.pause()
            b.allowCameraButton.setText(if (prefs.cameraBlocked) R.string.open_settings else R.string.allow_camera)
        }
    }

    private fun setTorch(on: Boolean) {
        if (torchOn == on) return
        torchOn = on
        if (hasCamera()) b.barcodeView.setTorch(on)
        b.torchButton.setIconResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun onCameraCode(text: String) {
        if (busy) return
        if (text == lastCode && SystemClock.elapsedRealtime() < ignoreLastUntil) return
        lastCode = text
        when (val parsed = UpiPayment.parse(text)) {
            is UpiPayment.Parsed.Ok -> {
                b.root.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY,
                )
                startPayment(parsed.payment)
            }
            is UpiPayment.Parsed.Failed -> {
                ignoreLastUntil = SystemClock.elapsedRealtime() + 3_000
                Snackbar.make(b.root, parsed.error.message, Snackbar.LENGTH_SHORT).setAnchorView(b.panel).show()
            }
        }
    }

    // ---- Payment entry points ----

    internal fun startPayment(payment: UpiPayment) {
        busy = true
        lastCode = payment.raw
        b.overlay.setDetected(true)
        flow.start(payment)
    }

    private fun finishHandling(holdMillis: Long) {
        busy = false
        ignoreLastUntil = SystemClock.elapsedRealtime() + holdMillis
        b.overlay.setDetected(false)
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun handleImage(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching { QrImageDecoder.decode(contentResolver, uri) }.getOrNull()
            }
            when (val parsed = text?.let(UpiPayment::parse)) {
                null -> Toast.makeText(this@MainActivity, R.string.no_qr_in_image, Toast.LENGTH_LONG).show()
                is UpiPayment.Parsed.Ok -> startPayment(parsed.payment)
                is UpiPayment.Parsed.Failed -> showParseError(parsed.error)
            }
        }
    }

    private fun showParseError(error: UpiPayment.ParseError) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cannot_pay_title)
            .setMessage(error.message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManualEntry() {
        val d = DialogManualBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pay_to_upi_id)
            .setIcon(R.drawable.ic_at)
            .setView(d.root)
            .setPositiveButton(R.string.pay, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = d.upiIdInput.text?.toString().orEmpty().trim()
                d.upiIdLayout.error = null
                d.amountLayout.error = null
                if (id.isNotEmpty() && id.all { it.isDigit() }) {
                    // A bare mobile number is not a UPI address; the handle is required.
                    d.upiIdLayout.error = getString(R.string.full_upi_id_needed, id)
                    return@setOnClickListener
                }
                val parsed = UpiPayment.build(
                    id,
                    d.nameInput.text?.toString(),
                    d.amountInput.text?.toString(),
                    d.noteInput.text?.toString(),
                )
                when (parsed) {
                    is UpiPayment.Parsed.Ok -> {
                        dialog.dismiss()
                        startPayment(parsed.payment)
                    }
                    is UpiPayment.Parsed.Failed -> when (parsed.error) {
                        is UpiPayment.ParseError.InvalidAmount -> d.amountLayout.error = parsed.error.message
                        else -> d.upiIdLayout.error = parsed.error.message
                    }
                }
            }
        }
        dialog.show()
    }

    // ---- Settings ----

    /** The default app if it is still installed and takes UPI links, else null. */
    private fun currentDefault(apps: List<UpiApp> = UpiApps.discover(this, prefs)): UpiApp? =
        apps.firstOrNull { it.packageName == prefs.defaultPackage && it.acceptsLinks }

    private fun refreshDefaultApp() {
        val default = currentDefault()
        b.defaultAppText.text = default?.label ?: getString(R.string.choose_default)
        if (default?.icon != null) b.defaultAppIcon.setImageDrawable(default.icon)
        else b.defaultAppIcon.setImageResource(R.mipmap.ic_launcher)
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val s = SheetSettingsBinding.inflate(layoutInflater)
        fun showDefault() { s.defaultValue.text = currentDefault()?.label ?: getString(R.string.not_set) }
        showDefault()
        s.defaultRow.setOnClickListener { chooseDefault { showDefault() } }
        s.alwaysAskSwitch.isChecked = prefs.alwaysAsk
        s.alwaysAskSwitch.setOnCheckedChangeListener { _, v -> prefs.alwaysAsk = v }
        val apps = UpiApps.discover(this, prefs)
        s.appsValue.text = getString(R.string.apps_shown_count, apps.count { it.visible }, apps.size)
        s.appsRow.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ManageAppsActivity::class.java))
        }
        s.addWidgetButton.setOnClickListener {
            if (!ScanWidgetProvider.requestPin(this)) {
                Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show()
            }
            dialog.dismiss()
        }
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        s.versionText.text = "${getString(R.string.app_name)} $version · $packageName"
        dialog.setContentView(s.root)
        dialog.show()
    }

    /** Lists every installed UPI app (with icons) and saves the one picked as default. */
    private fun chooseDefault(onChanged: () -> Unit = {}) {
        val apps = UpiApps.discover(this, prefs).filter { it.acceptsLinks }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_upi_apps, Toast.LENGTH_LONG).show()
            return
        }
        val current = currentDefault(apps)?.packageName
        val adapter = object : ArrayAdapter<UpiApp>(this, R.layout.item_default_app, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_default_app, parent, false)
                val app = apps[position]
                row.findViewById<TextView>(R.id.label).text = app.label
                val icon = row.findViewById<ImageView>(R.id.icon)
                if (app.icon != null) icon.setImageDrawable(app.icon) else icon.setImageResource(android.R.drawable.sym_def_app_icon)
                row.findViewById<View>(R.id.check).visibility = if (app.packageName == current) View.VISIBLE else View.INVISIBLE
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_default_title)
            .setAdapter(adapter) { dialog, which ->
                prefs.defaultPackage = apps[which].packageName
                prefs.setVisible(apps[which].packageName, true)
                dialog.dismiss()
                refreshDefaultApp()
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showResult(r: UpiResponse) {
        val (text, icon, color) = when (r.status) {
            UpiResponse.Status.SUCCESS -> Triple(R.string.result_success, R.drawable.ic_check_circle, R.color.success)
            UpiResponse.Status.SUBMITTED -> Triple(R.string.result_submitted, R.drawable.ic_info, R.color.warning)
            UpiResponse.Status.FAILURE -> Triple(R.string.result_failure, R.drawable.ic_info, R.color.error)
            UpiResponse.Status.UNKNOWN -> Triple(R.string.result_unknown, R.drawable.ic_info, R.color.brand_on_surface_variant)
        }
        b.resultText.text = listOfNotNull(
            getString(text),
            r.transactionId?.let { "Txn $it" },
            r.approvalRef?.let { "Ref $it" },
        ).joinToString(" · ")
        b.resultIcon.setImageResource(icon)
        b.resultIcon.setColorFilter(ContextCompat.getColor(this, color))
        b.resultRow.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
