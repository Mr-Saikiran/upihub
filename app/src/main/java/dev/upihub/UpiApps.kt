package dev.upihub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

data class UpiApp(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val preferred: Boolean,
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
     * Lists the preferred apps (installed or not) followed, when [includeOthers]
     * is set, by any other installed app that handles `upi://pay`. The
     * [defaultPackage] is always first.
     */
    fun discover(context: Context, defaultPackage: String, includeOthers: Boolean): List<UpiApp> {
        val pm = context.packageManager
        val handlers = queryHandlers(pm)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .toSet()

        val preferred = PREFERRED.map { p ->
            val installed = p.packageName in handlers
            UpiApp(
                packageName = p.packageName,
                label = p.label,
                installed = installed,
                preferred = true,
                icon = if (installed) loadIcon(pm, p.packageName) else null,
            )
        }
        val others = if (!includeOthers) emptyList() else handlers
            .filter { pkg -> PREFERRED.none { it.packageName == pkg } }
            .map { pkg ->
                UpiApp(pkg, loadLabel(pm, pkg), installed = true, preferred = false, icon = loadIcon(pm, pkg))
            }
            .sortedBy { it.label.lowercase() }

        return (preferred + others).sortedWith(
            compareByDescending<UpiApp> { it.packageName == defaultPackage }
                .thenByDescending { it.installed },
        )
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
