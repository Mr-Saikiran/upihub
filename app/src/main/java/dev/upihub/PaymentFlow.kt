package dev.upihub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.upihub.databinding.ItemAppBinding
import dev.upihub.databinding.SheetChooserBinding

/**
 * Shared "which app should pay?" step. Shows the chooser (default preselected)
 * or, when the user turned off "ask every time", opens the default directly.
 * [launch] receives the intent targeted at the chosen app.
 */
class PaymentFlow(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val launch: (Intent) -> Unit,
    private val onCancel: () -> Unit,
) {
    fun start(payment: UpiPayment) {
        val apps = UpiApps.discover(activity, prefs.defaultPackage, prefs.showOtherApps)
        val installed = apps.filter { it.installed }
        if (installed.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.no_upi_apps)
                .setPositiveButton(R.string.install_kiwi) { _, _ -> openStore(UpiApps.DEFAULT_PACKAGE) }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { onCancel() }
                .show()
            return
        }
        val default = installed.firstOrNull { it.packageName == prefs.defaultPackage }
        if (!prefs.alwaysAsk && default != null) {
            pay(payment, default.packageName)
            return
        }
        showChooser(payment, apps, default?.packageName ?: installed.first().packageName)
    }

    private fun showChooser(payment: UpiPayment, apps: List<UpiApp>, initial: String) {
        val dialog = BottomSheetDialog(activity)
        val b = SheetChooserBinding.inflate(activity.layoutInflater)
        var selected = initial
        var launched = false

        b.payeeText.text = payment.payeeName ?: payment.payeeAddress
        b.detailText.text = listOfNotNull(
            payment.amount?.let { "₹$it" },
            payment.payeeAddress.takeIf { payment.payeeName != null },
            payment.note,
        ).joinToString(" · ")

        fun refreshButton() {
            b.payButton.text = activity.getString(R.string.pay_with, apps.first { it.packageName == selected }.label)
            b.rememberCheck.visibility = if (selected == prefs.defaultPackage) ViewGroup.GONE else ViewGroup.VISIBLE
        }

        val adapter = AppAdapter(apps, { selected }) { app ->
            if (app.installed) {
                selected = app.packageName
                refreshButton()
            } else {
                openStore(app.packageName)
            }
        }
        b.appList.layoutManager = LinearLayoutManager(activity)
        b.appList.adapter = adapter
        refreshButton()

        b.payButton.setOnClickListener {
            if (b.rememberCheck.isChecked) prefs.defaultPackage = selected
            launched = true
            dialog.dismiss()
            pay(payment, selected)
        }
        dialog.setOnDismissListener { if (!launched) onCancel() }
        dialog.setContentView(b.root)
        dialog.show()
    }

    private fun pay(payment: UpiPayment, packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payment.raw)).setPackage(packageName)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "${UpiApps.labelFor(packageName)} could not open this payment.", Toast.LENGTH_LONG).show()
            onCancel()
        }
    }

    private fun openStore(packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        try {
            activity.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
            )
        }
    }

    private inner class AppAdapter(
        private val apps: List<UpiApp>,
        private val selected: () -> String,
        private val onClick: (UpiApp) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {
        inner class Holder(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.b.label.text = app.label
            holder.b.status.text = when {
                !app.installed -> activity.getString(R.string.not_installed)
                app.packageName == prefs.defaultPackage -> activity.getString(R.string.default_label)
                else -> activity.getString(R.string.installed)
            }
            if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
            else holder.b.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.b.root.alpha = if (app.installed) 1f else 0.5f
            holder.b.radio.visibility = if (app.installed) ViewGroup.VISIBLE else ViewGroup.INVISIBLE
            holder.b.radio.isChecked = app.packageName == selected()
            holder.b.root.setOnClickListener {
                val before = apps.indexOfFirst { it.packageName == selected() }
                onClick(app)
                val after = apps.indexOfFirst { it.packageName == selected() }
                if (before != after) {
                    if (before >= 0) notifyItemChanged(before)
                    notifyItemChanged(after)
                }
            }
        }
    }
}
