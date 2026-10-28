package dev.upihub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

/** An installed app that handles `upi://pay`. [visible] apps appear in the payment list. */
data class UpiApp(
    val packageName: String,
    val label: String,
    val preferred: Boolean,
    val visible: Boolean,
    val icon: Drawable?,
)

object UpiApps {
    data class Preferred(val packageName: String, val label: String)

    /** Package IDs verified against their Google Play (India) listings. */
    val PREFERRED = listOf(
        Preferred("in.gokiwi.kiwitpap", "Kiwi"),
        Preferred("in.indwealth", "INDmoney"),
        Preferred("net.one97.paytm", "Paytm"),
        Preferred("com.dreamplug.androidapp", "CRED"),
        Preferred("com.mobikwik_new", "MobiKwik"),
        Preferred("money.jupiter", "Jupiter"),
        Preferred("com.google.android.apps.nbu.paisa.user", "Google Pay"),
        Preferred("com.phonepe.app", "PhonePe"),
        Preferred("in.org.npci.upiapp", "BHIM"),
    )

    const val DEFAULT_PACKAGE = "in.gokiwi.kiwitpap"

    fun labelFor(packageName: String): String =
        PREFERRED.firstOrNull { it.packageName == packageName }?.label ?: packageName

    /** The app's launcher icon, or null when it is not installed. */
    fun iconFor(context: Context, packageName: String): Drawable? = loadIcon(context.packageManager, packageName)

    /**
     * Every installed UPI app, in the user's order. Apps the user never
     * arranged (first run, or newly installed) follow it: the default app,
     * then the preferred apps in [PREFERRED] order, then the rest A–Z.
     * Apps that are not installed are never listed.
     */
    fun discover(context: Context, prefs: Prefs): List<UpiApp> {
        val pm = context.packageManager
        val handlers = queryHandlers(pm)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .toSet()
        val default = prefs.defaultPackage

        val preferred = PREFERRED.filter { it.packageName in handlers }.map { p ->
            UpiApp(p.packageName, p.label, preferred = true, visible = false, icon = loadIcon(pm, p.packageName))
        }
        val others = handlers
            .filter { pkg -> PREFERRED.none { it.packageName == pkg } }
            .map { pkg -> UpiApp(pkg, loadLabel(pm, pkg), preferred = false, visible = false, icon = loadIcon(pm, pkg)) }
            .sortedBy { it.label.lowercase() }
        val baseline = (preferred + others).sortedByDescending { it.packageName == default }

        val order = prefs.appOrder
        // Stable sort: arranged apps by their saved position, the rest keep the baseline order after them.
        return baseline
            .sortedBy { app -> order.indexOf(app.packageName).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.copy(visible = it.packageName == default || prefs.isVisible(it.packageName, it.preferred)) }
    }

    private fun queryHandlers(pm: PackageManager): List<ResolveInfo> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=probe@upi"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun loadIcon(pm: PackageManager, pkg: String): Drawable? =
        try {
            pm.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun loadLabel(pm: PackageManager, pkg: String): String =
        try {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
}
