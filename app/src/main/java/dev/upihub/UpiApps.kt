package dev.upihub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

/**
 * An installed UPI app. [component] is the activity that takes `upi://pay`
 * links; it is null for a supported app that is installed but does not accept
 * links from other apps. Only [visible] apps with a component are offered.
 */
data class UpiApp(
    val packageName: String,
    val label: String,
    val preferred: Boolean,
    val visible: Boolean,
    val icon: Drawable?,
    val component: ComponentName?,
    /**
     * The activity that takes a shared image (ACTION_SEND with an image type), used for
     * NPCI's "QR Share and Pay": the app decodes the merchant's QR itself.
     * Google Pay and PhonePe declare one; null when the app has none.
     */
    val shareTarget: ComponentName? = null,
) {
    val acceptsLinks: Boolean get() = component != null
    val acceptsQrImages: Boolean get() = shareTarget != null
}

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
        Preferred("money.super.payments", "super.money"),
        Preferred("in.org.npci.upiapp", "BHIM"),
    )

    fun labelFor(context: Context, packageName: String): String =
        PREFERRED.firstOrNull { it.packageName == packageName }?.label ?: loadLabel(context.packageManager, packageName)

    /** The app's launcher icon, or null when it is not installed. */
    fun iconFor(context: Context, packageName: String): Drawable? = loadIcon(context.packageManager, packageName)

    /**
     * Every installed UPI app, in the user's order. Apps the user never
     * arranged (first run, or newly installed) follow it: the default app,
     * then the supported apps in [PREFERRED] order, then the rest A–Z, then
     * supported apps that do not accept links. Apps that are not installed
     * are never listed.
     */
    fun discover(context: Context, prefs: Prefs): List<UpiApp> {
        val pm = context.packageManager
        val handlers = LinkedHashMap<String, ComponentName>()
        for (info in queryHandlers(pm)) {
            val activity = info.activityInfo ?: continue
            if (activity.packageName == context.packageName || !activity.exported) continue
            handlers.putIfAbsent(activity.packageName, ComponentName(activity.packageName, activity.name))
        }

        val preferred = PREFERRED
            .filter { it.packageName in handlers || isInstalled(pm, it.packageName) }
            .map { p ->
                UpiApp(p.packageName, p.label, true, false, loadIcon(pm, p.packageName), handlers[p.packageName],
                    shareTarget(pm, p.packageName))
            }
        val others = handlers
            .filterKeys { pkg -> PREFERRED.none { it.packageName == pkg } }
            .map { (pkg, component) ->
                UpiApp(pkg, loadLabel(pm, pkg), false, false, loadIcon(pm, pkg), component, shareTarget(pm, pkg))
            }
            .sortedBy { it.label.lowercase() }
        val default = prefs.defaultPackage
        val baseline = (preferred + others)
            .sortedWith(compareByDescending<UpiApp> { it.acceptsLinks }.thenByDescending { it.packageName == default })

        val order = prefs.appOrder
        // Stable sort: arranged apps by their saved position, the rest keep the baseline order after them.
        return baseline
            .sortedBy { app -> order.indexOf(app.packageName).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.copy(visible = it.acceptsLinks && (it.packageName == default || prefs.isVisible(it.packageName))) }
    }

    /**
     * All activities that take a `upi://pay` link. Not limited to
     * CATEGORY_DEFAULT: some apps omit it, and they are opened explicitly.
     */
    private fun queryHandlers(pm: PackageManager): List<ResolveInfo> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=probe@upi&pn=Probe&cu=INR"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, 0)
        }
    }

    /** The app's exported activity that accepts a shared PNG, if any. */
    private fun shareTarget(pm: PackageManager, pkg: String): ComponentName? {
        val probe = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(pkg)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return matches.firstNotNullOfOrNull { info ->
            info.activityInfo?.takeIf { it.exported }?.let { ComponentName(it.packageName, it.name) }
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
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
