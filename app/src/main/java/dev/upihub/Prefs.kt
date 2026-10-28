package dev.upihub

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("upi_hub", Context.MODE_PRIVATE)

    var defaultPackage: String
        get() = sp.getString(KEY_DEFAULT, UpiApps.DEFAULT_PACKAGE) ?: UpiApps.DEFAULT_PACKAGE
        set(value) = sp.edit().putString(KEY_DEFAULT, value).apply()

    /** When false and the default app is installed, payments open it directly. */
    var alwaysAsk: Boolean
        get() = sp.getBoolean(KEY_ALWAYS_ASK, true)
        set(value) = sp.edit().putBoolean(KEY_ALWAYS_ASK, value).apply()

    /** Package names in the order the user arranged the payment list. */
    var appOrder: List<String>
        get() = sp.getString(KEY_APP_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = sp.edit().putString(KEY_APP_ORDER, value.joinToString(",")).apply()

    /**
     * Whether an app appears in the payment list. Without an explicit choice,
     * the nine supported apps are shown and other UPI apps are hidden (unless
     * the older "show other apps" switch was turned on).
     */
    fun isVisible(packageName: String, preferred: Boolean): Boolean = when (packageName) {
        in stringSet(KEY_SHOWN) -> true
        in stringSet(KEY_HIDDEN) -> false
        else -> preferred || sp.getBoolean(KEY_LEGACY_SHOW_OTHERS, false)
    }

    fun setVisible(packageName: String, visible: Boolean) {
        val shown = stringSet(KEY_SHOWN).toMutableSet()
        val hidden = stringSet(KEY_HIDDEN).toMutableSet()
        if (visible) { shown += packageName; hidden -= packageName } else { hidden += packageName; shown -= packageName }
        sp.edit().putStringSet(KEY_SHOWN, shown).putStringSet(KEY_HIDDEN, hidden).apply()
    }

    /** Forgets the arranged order and every show/hide choice. */
    fun resetAppList() {
        sp.edit().remove(KEY_APP_ORDER).remove(KEY_SHOWN).remove(KEY_HIDDEN).remove(KEY_LEGACY_SHOW_OTHERS).apply()
    }

    private fun stringSet(key: String): Set<String> = sp.getStringSet(key, null) ?: emptySet()

    /** Set when the user denied the camera and Android will no longer show the prompt. */
    var cameraBlocked: Boolean
        get() = sp.getBoolean(KEY_CAMERA_BLOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_CAMERA_BLOCKED, value).apply()

    private companion object {
        const val KEY_DEFAULT = "default_package"
        const val KEY_ALWAYS_ASK = "always_ask"
        const val KEY_APP_ORDER = "app_order"
        const val KEY_SHOWN = "shown_apps"
        const val KEY_HIDDEN = "hidden_apps"
        const val KEY_LEGACY_SHOW_OTHERS = "show_other_apps"
        const val KEY_CAMERA_BLOCKED = "camera_blocked"
    }
}
