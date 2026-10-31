package dev.upihub

import android.content.ActivityNotFoundException
import android.content.ClipData
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
 *
 * Each app gets the payment one of two ways ([Delivery]): as the QR image
 * (NPCI's "QR Share and Pay", the app decodes the merchant's QR itself) when
 * it takes one, else as a `upi://pay` link through [launch], which expects a
 * result. A share returns no result, so [onShared] reports it instead.
 * [canShareQr] is false when relaying another app's link, whose caller waits
 * for a result.
 */
class PaymentFlow(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val launch: (Intent) -> Unit,
    private val onCancel: () -> Unit,
    private val onShared: (UpiApp) -> Unit = {},
    private val canShareQr: Boolean = true,
) {
    enum class Delivery { QR_IMAGE, LINK }

    /** The last payment handed over as a link (original QR, app), for retrying another way. */
    var lastLinkAttempt: Pair<UpiPayment, UpiApp>? = null
        private set

    fun deliveryFor(app: UpiApp): Delivery =
        if (canShareQr && prefs.shareQrImage && app.acceptsQrImages) Delivery.QR_IMAGE else Delivery.LINK

    /** An amount must be typed here only for a link to an unsigned QR without one. */
    private fun needsAmount(payment: UpiPayment, app: UpiApp) =
        deliveryFor(app) == Delivery.LINK && payment.amount == null && !payment.signed

    fun start(payment: UpiPayment) {
        val installed = UpiApps.discover(activity, prefs).filter { it.acceptsLinks }
        if (installed.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.no_upi_apps)
                .setPositiveButton(R.string.find_upi_app) { _, _ -> openStoreSearch() }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { onCancel() }
                .show()
            return
        }
        // Hidden apps stay out of the list; should every app be hidden, show them all rather than none.
        val apps = installed.filter { it.visible }.ifEmpty { installed }
        val default = apps.firstOrNull { it.packageName == prefs.defaultPackage }
        // The sheet is still needed when an amount has to be typed here.
        if (!prefs.alwaysAsk && default != null && !needsAmount(payment, default)) {
            pay(payment, payment, default)
            return
        }
        showChooser(payment, apps, default?.packageName ?: apps.first().packageName)
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

        fun selectedApp() = apps.first { it.packageName == selected }

        /** The payment to send, or null while a required amount is missing or invalid. */
        fun paymentToSend(): UpiPayment? {
            if (!needsAmount(payment, selectedApp())) return payment
            val typed = b.amountInput.text?.toString().orEmpty().trim()
            return (payment.withAmount(typed) as? UpiPayment.Parsed.Ok)?.payment
        }

        fun refreshButton() {
            val app = selectedApp()
            val delivery = deliveryFor(app)
            val askAmount = needsAmount(payment, app)
            b.amountLayout.visibility = if (askAmount) ViewGroup.VISIBLE else ViewGroup.GONE
            val note = when {
                delivery == Delivery.QR_IMAGE && payment.amount == null ->
                    activity.getString(R.string.note_qr_image_amount, app.label)
                delivery == Delivery.QR_IMAGE -> activity.getString(R.string.note_qr_image, app.label)
                payment.signed && payment.amount == null -> activity.getString(R.string.note_signed_amount, app.label)
                else -> null
            }
            b.deliveryNote.text = note
            b.deliveryNote.visibility = if (note == null) ViewGroup.GONE else ViewGroup.VISIBLE
            val toSend = paymentToSend()
            b.payButton.isEnabled = toSend != null
            b.payButton.text = when {
                delivery == Delivery.QR_IMAGE -> activity.getString(R.string.send_qr_to, app.label)
                askAmount && toSend?.amount != null -> activity.getString(R.string.pay_amount_with, toSend.amount, app.label)
                else -> activity.getString(R.string.pay_with, app.label)
            }
            b.rememberCheck.visibility = if (selected == prefs.defaultPackage) ViewGroup.GONE else ViewGroup.VISIBLE
        }
        // With no default yet, the first app picked becomes it unless unticked.
        b.rememberCheck.isChecked = prefs.defaultPackage == null
        b.amountInput.doAfterTextChanged { refreshButton() }

        val adapter = AppAdapter(apps, { selected }) { app ->
            selected = app.packageName
            refreshButton()
        }
        b.appList.layoutManager = LinearLayoutManager(activity)
        b.appList.adapter = adapter
        refreshButton()

        b.payButton.setOnClickListener {
            val toSend = paymentToSend() ?: return@setOnClickListener
            if (b.rememberCheck.isChecked) prefs.defaultPackage = selected
            launched = true
            dialog.dismiss()
            pay(payment, toSend, selectedApp())
        }
        dialog.setOnDismissListener { if (!launched) onCancel() }
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        if (needsAmount(payment, selectedApp())) b.amountInput.requestFocus()
    }

    /** [original] is the QR as scanned; [toSend] may add a typed amount for a link. */
    private fun pay(original: UpiPayment, toSend: UpiPayment, app: UpiApp) {
        when (deliveryFor(app)) {
            Delivery.QR_IMAGE -> if (!shareQr(original, app)) onCancel()
            Delivery.LINK -> sendLink(original, toSend, app)
        }
    }

    /**
     * Opens the app's own UPI activity explicitly, so apps whose link handler
     * lacks CATEGORY_DEFAULT still receive the payment.
     */
    private fun sendLink(original: UpiPayment, toSend: UpiPayment, app: UpiApp) {
        val component = app.component ?: return onCancel()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(toSend.raw))
            .setPackage(app.packageName)
            .setComponent(component)
        try {
            lastLinkAttempt = original to app
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            cannotOpen(app)
        } catch (_: SecurityException) {
            cannotOpen(app)
        }
    }

    /**
     * "QR Share and Pay": shares the original QR, drawn as an image, with the
     * app's share target. The app decodes and checks the merchant's QR itself.
     */
    private fun shareQr(payment: UpiPayment, app: UpiApp): Boolean {
        val target = app.shareTarget ?: return false
        return try {
            val uri = QrImage.share(activity, payment.raw)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setComponent(target)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(null, uri)
            activity.startActivity(intent)
            onShared(app)
            true
        } catch (_: ActivityNotFoundException) {
            cannotOpen(app, notify = false)
            false
        } catch (_: SecurityException) {
            cannotOpen(app, notify = false)
            false
        }
    }

    /** After a declined link: shares the same QR with the same app, if it takes images. */
    fun retryAsQrImage(): Boolean {
        val (payment, app) = lastLinkAttempt ?: return false
        return app.acceptsQrImages && shareQr(payment, app)
    }

    /**
     * After a declined link, for an app that takes no images: saves the QR to
     * Photos and opens the app, whose scanner can read it from the gallery.
     */
    fun saveQrAndOpenApp(): Boolean {
        val (payment, app) = lastLinkAttempt ?: return false
        val saved = QrImage.saveToPhotos(activity, payment.raw)
        if (saved == null) {
            // Android 9 and older: let the user save or send the image themselves.
            val share = Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, QrImage.share(activity, payment.raw))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.save_qr)))
            return true
        }
        val open = activity.packageManager.getLaunchIntentForPackage(app.packageName)
        Toast.makeText(activity, activity.getString(R.string.qr_saved_open_app, app.label), Toast.LENGTH_LONG).show()
        if (open != null) activity.startActivity(open)
        return true
    }

    private fun cannotOpen(app: UpiApp, notify: Boolean = true) {
        Toast.makeText(activity, activity.getString(R.string.app_cannot_open, app.label), Toast.LENGTH_LONG).show()
        if (notify) onCancel()
    }

    private fun openStoreSearch() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=UPI&c=apps"))
        try {
            activity.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=UPI&c=apps")))
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
            // Says how the payment reaches the app, so the choice is never hidden.
            val how = activity.getString(
                if (deliveryFor(app) == Delivery.QR_IMAGE) R.string.via_qr_image else R.string.via_link)
            holder.b.status.text = if (app.packageName == prefs.defaultPackage) {
                activity.getString(R.string.default_with, how)
            } else {
                how
            }
            if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
            else holder.b.icon.setImageResource(android.R.drawable.sym_def_app_icon)
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
