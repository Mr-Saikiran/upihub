package dev.upihub

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Receives `upi://pay` links from browsers and other apps, lets the user pick
 * the paying app, and relays that app's result back to the original caller.
 */
class RouterActivity : AppCompatActivity() {
    private var forwarded = false

    private val payLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        setResult(r.resultCode, r.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwarded = savedInstanceState?.getBoolean(KEY_FORWARDED) ?: false
        if (forwarded) return // Waiting for the chosen app's result.

        val raw = intent?.dataString
        val parsed = raw?.let(UpiPayment::parse)
        if (parsed !is UpiPayment.Parsed.Ok) {
            val message = (parsed as? UpiPayment.Parsed.Failed)?.error?.message ?: "No payment link received."
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        PaymentFlow(
            this,
            Prefs(this),
            launch = { forwarded = true; payLauncher.launch(it) },
            onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
        ).start(parsed.payment)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FORWARDED, forwarded)
    }

    private companion object {
        const val KEY_FORWARDED = "forwarded"
    }
}
