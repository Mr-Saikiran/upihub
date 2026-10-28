package dev.upihub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.upihub.databinding.ActivityMainBinding
import dev.upihub.databinding.DialogManualBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var flow: PaymentFlow

    private val payLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        showResult(UpiResponse.parse(r.data?.getStringExtra("response")))
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let(::handleScanned)
    }

    // System photo picker: no storage permission needed.
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::handleImage)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        flow = PaymentFlow(this, prefs, launch = { payLauncher.launch(it) }, onCancel = {})

        b.scanButton.setOnClickListener { startScan() }
        b.galleryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.manualButton.setOnClickListener { showManualEntry() }
        b.changeDefaultButton.setOnClickListener { chooseDefault() }
        b.alwaysAskSwitch.isChecked = prefs.alwaysAsk
        b.alwaysAskSwitch.setOnCheckedChangeListener { _, v -> prefs.alwaysAsk = v }
        b.showOthersSwitch.isChecked = prefs.showOtherApps
        b.showOthersSwitch.setOnCheckedChangeListener { _, v -> prefs.showOtherApps = v; refresh() }

        if (savedInstanceState == null) sharedImage(intent)?.let(::handleImage)

        // Ask for the camera up front once, so the first scan opens immediately.
        if (!hasCamera() && !prefs.askedCameraOnce) {
            prefs.askedCameraOnce = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let(::handleImage)
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
            if (text == null) {
                Toast.makeText(this@MainActivity, R.string.no_qr_in_image, Toast.LENGTH_LONG).show()
            } else {
                handleScanned(text)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.defaultAppText.text = UpiApps.labelFor(prefs.defaultPackage)
        val apps = UpiApps.discover(this, prefs.defaultPackage, prefs.showOtherApps)
        b.installedText.text = apps.joinToString("\n") { "${if (it.installed) "✓" else "✗"}  ${it.label}" }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        // CaptureActivity requests the camera itself if it is still missing.
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun handleScanned(text: String) {
        when (val parsed = UpiPayment.parse(text)) {
            is UpiPayment.Parsed.Ok -> flow.start(parsed.payment)
            is UpiPayment.Parsed.Failed -> MaterialAlertDialogBuilder(this)
                .setTitle("Can't pay with this code")
                .setMessage(parsed.error.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showManualEntry() {
        val d = DialogManualBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pay_to_upi_id)
            .setView(d.root)
            .setPositiveButton(R.string.pay, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = d.upiIdInput.text?.toString().orEmpty().trim()
                d.upiIdLayout.error = null
                d.amountLayout.error = null
                if (id.all { it.isDigit() } && id.isNotEmpty()) {
                    // A bare mobile number is not a UPI address; the app must know the handle.
                    d.upiIdLayout.error = "Enter the full UPI ID, e.g. $id@paytm"
                    return@setOnClickListener
                }
                when (val parsed = UpiPayment.build(id, d.nameInput.text?.toString(), d.amountInput.text?.toString(), d.noteInput.text?.toString())) {
                    is UpiPayment.Parsed.Ok -> {
                        dialog.dismiss()
                        flow.start(parsed.payment)
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

    private fun chooseDefault() {
        val apps = UpiApps.discover(this, prefs.defaultPackage, prefs.showOtherApps).filter { it.installed || it.preferred }
        val labels = apps.map { if (it.installed) it.label else "${it.label} (not installed)" }.toTypedArray()
        val current = apps.indexOfFirst { it.packageName == prefs.defaultPackage }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_app)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.defaultPackage = apps[which].packageName
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun showResult(r: UpiResponse) {
        val text = when (r.status) {
            UpiResponse.Status.SUCCESS -> "Payment successful"
            UpiResponse.Status.SUBMITTED -> "Payment submitted (pending)"
            UpiResponse.Status.FAILURE -> "Payment failed"
            UpiResponse.Status.UNKNOWN -> "Returned from UPI app (status not reported; check the app)"
        }
        b.lastResultText.text = listOfNotNull(
            "Last payment: $text",
            r.transactionId?.let { "Txn ID: $it" },
            r.approvalRef?.let { "Ref: $it" },
            r.responseCode?.let { "Code: $it" },
        ).joinToString("\n")
        b.lastResultText.visibility = View.VISIBLE
    }
}
