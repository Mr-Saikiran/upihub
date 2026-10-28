package dev.upihub

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("upi_hub", Context.MODE_PRIVATE).also(::migrate)

    /**
     * 0.1–0.3 preselected Kiwi, hid non-supported apps behind a switch and kept
     * explicit "shown" choices. Now every app is shown unless hidden and the
     * user picks the default, so drop those keys (and a Kiwi default) once.
     */
    private fun migrate(sp: android.content.SharedPreferences) {
        if (sp.getInt(KEY_SCHEMA, 1) >= 2) return
        sp.edit().apply {
            remove("show_other_apps")
            remove("shown_apps")
            if (sp.getString(KEY_DEFAULT, null) == "in.gokiwi.kiwitpap") remove(KEY_DEFAULT)
            putInt(KEY_SCHEMA, 2)
        }.apply()
    }

    /** The app preselected for payments; null until the user picks one. */
    var defaultPackage: String?
        get() = sp.getString(KEY_DEFAULT, null)
        set(value) = sp.edit().apply { if (value == null) remove(KEY_DEFAULT) else putString(KEY_DEFAULT, value) }.apply()

    /** When false and the default app is installed, payments open it directly. */
    var alwaysAsk: Boolean
        get() = sp.getBoolean(KEY_ALWAYS_ASK, true)
        set(value) = sp.edit().putBoolean(KEY_ALWAYS_ASK, value).apply()

    /** Package names in the order the user arranged the payment list. */
    var appOrder: List<String>
        get() = sp.getString(KEY_APP_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = sp.edit().putString(KEY_APP_ORDER, value.joinToString(",")).apply()

    /** Every installed UPI app appears in the payment list unless the user hid it. */
    fun isVisible(packageName: String): Boolean = packageName !in stringSet(KEY_HIDDEN)

    fun setVisible(packageName: String, visible: Boolean) {
        val hidden = stringSet(KEY_HIDDEN).toMutableSet()
        if (visible) hidden -= packageName else hidden += packageName
        sp.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    /** Forgets the arranged order and every hide choice. */
    fun resetAppList() {
        sp.edit().remove(KEY_APP_ORDER).remove(KEY_HIDDEN).apply()
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
        const val KEY_HIDDEN = "hidden_apps"
        const val KEY_SCHEMA = "prefs_schema"
        const val KEY_CAMERA_BLOCKED = "camera_blocked"
    }
}
