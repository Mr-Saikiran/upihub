package dev.upihub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.upihub.databinding.ActivityPickerBinding
import dev.upihub.databinding.ItemAppTileBinding

/**
 * Home: pick the UPI app first, then it scans the QR itself.
 *
 * Payment apps decline payments handed over by another app (a `upi://` link
 * or a shared QR image) far more often than QRs scanned in the app, so the
 * reliable path is to open the chosen app and let its own scanner start the
 * payment. None of the analysed apps lets another app open its scanner screen
 * directly (PhonePe's and INDmoney's are not exported; Google Pay's is inside
 * its Flutter UI), so the app opens on its home screen, one tap from Scan.
 */
class PickerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPickerBinding
    private lateinit var prefs: Prefs
    private var apps: List<UpiApp> = emptyList()
    private val adapter = TileAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.topBar.updatePadding(top = bars.top + dp(12))
            b.footer.updatePadding(bottom = bars.bottom + dp(16))
            insets
        }
        b.appGrid.layoutManager = GridLayoutManager(this, 3)
        b.appGrid.adapter = adapter
        b.manageButton.setOnClickListener { startActivity(Intent(this, ManageAppsActivity::class.java)) }
        b.scanHereButton.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val installed = UpiApps.discover(this, prefs).filter { it.acceptsLinks }
        // Hidden apps stay out of the grid; should every app be hidden, show them all rather than none.
        apps = installed.filter { it.visible }.ifEmpty { installed }
        adapter.notifyDataSetChanged()
        b.emptyText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        b.appGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Opens [app] so its own scanner can start the payment. */
    internal fun openApp(app: UpiApp) {
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch == null) {
            Toast.makeText(this, getString(R.string.app_cannot_open, app.label), Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(launch)
            Toast.makeText(this, getString(R.string.opening_app_tap_scan, app.label), Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.app_cannot_open, app.label), Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.app_cannot_open, app.label), Toast.LENGTH_LONG).show()
        }
    }

    private fun showOptions(app: UpiApp) {
        val isDefault = app.packageName == prefs.defaultPackage
        val options = buildList {
            if (!isDefault) add(getString(R.string.make_default) to { prefs.defaultPackage = app.packageName; refresh() })
            if (!isDefault) add(getString(R.string.hide_app) to { prefs.setVisible(app.packageName, false); refresh() })
            add(getString(R.string.manage_apps) to { startActivity(Intent(this@PickerActivity, ManageAppsActivity::class.java)) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class TileAdapter : RecyclerView.Adapter<TileAdapter.Holder>() {
        inner class Holder(val b: ItemAppTileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.b.label.text = app.label
            if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
            else holder.b.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.b.badge.visibility = if (app.packageName == prefs.defaultPackage) View.VISIBLE else View.GONE
            holder.b.card.contentDescription = getString(R.string.scan_with_app, app.label)
            holder.b.card.setOnClickListener { openApp(app) }
            holder.b.card.setOnLongClickListener { showOptions(app); true }
        }
    }
}
