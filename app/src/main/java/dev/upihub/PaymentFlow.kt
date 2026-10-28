package dev.upihub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
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
        // Without an amount the sheet is still needed to collect one.
        if (!prefs.alwaysAsk && default != null && payment.amount != null) {
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

        val title = payment.payeeName ?: payment.payeeAddress
        b.avatar.text = title.first().uppercaseChar().toString()
        b.payeeText.text = title
        b.detailText.text = listOfNotNull(
            payment.payeeAddress.takeIf { payment.payeeName != null },
            payment.note,
        ).joinToString(" · ")
        b.amountText.text = payment.amount?.let { "₹$it" }.orEmpty()

        val needsAmount = payment.amount == null
        b.amountLayout.visibility = if (needsAmount) ViewGroup.VISIBLE else ViewGroup.GONE

        /** The payment to send, or null while a required amount is missing or invalid. */
        fun paymentToSend(): UpiPayment? {
            if (!needsAmount) return payment
            val typed = b.amountInput.text?.toString().orEmpty().trim()
            return (payment.withAmount(typed) as? UpiPayment.Parsed.Ok)?.payment
        }

        fun refreshButton() {
            val label = apps.first { it.packageName == selected }.label
            val toSend = paymentToSend()
            b.payButton.isEnabled = toSend != null
            b.payButton.text = if (needsAmount && toSend?.amount != null) {
                activity.getString(R.string.pay_amount_with, toSend.amount, label)
            } else {
                activity.getString(R.string.pay_with, label)
            }
            b.rememberCheck.visibility = if (selected == prefs.defaultPackage) ViewGroup.GONE else ViewGroup.VISIBLE
        }
        b.amountInput.doAfterTextChanged { refreshButton() }

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
            val toSend = paymentToSend() ?: return@setOnClickListener
            if (b.rememberCheck.isChecked) prefs.defaultPackage = selected
            launched = true
            dialog.dismiss()
            pay(toSend, selected)
        }
        dialog.setOnDismissListener { if (!launched) onCancel() }
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        if (needsAmount) b.amountInput.requestFocus()
    }

    private fun pay(payment: UpiPayment, packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payment.raw)).setPackage(packageName)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.app_cannot_open, UpiApps.labelFor(packageName)), Toast.LENGTH_LONG).show()
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
            val isSelected = app.packageName == selected()
            holder.b.label.text = app.label
            holder.b.status.text = when {
                !app.installed -> activity.getString(R.string.not_installed)
                app.packageName == prefs.defaultPackage -> activity.getString(R.string.default_label)
                else -> activity.getString(R.string.installed)
            }
            if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
            else holder.b.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.b.root.alpha = if (app.installed) 1f else 0.55f
            holder.b.check.visibility = if (isSelected) ViewGroup.VISIBLE else ViewGroup.INVISIBLE
            val density = activity.resources.displayMetrics.density
            holder.b.card.strokeWidth = ((if (isSelected) 2 else 1) * density).toInt()
            holder.b.card.strokeColor = MaterialColors.getColor(
                holder.b.card,
                if (isSelected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutline,
            )
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
