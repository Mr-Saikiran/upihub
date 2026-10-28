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

    var showOtherApps: Boolean
        get() = sp.getBoolean(KEY_SHOW_OTHERS, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_OTHERS, value).apply()

    /** Set when the user denied the camera and Android will no longer show the prompt. */
    var cameraBlocked: Boolean
        get() = sp.getBoolean(KEY_CAMERA_BLOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_CAMERA_BLOCKED, value).apply()

    private companion object {
        const val KEY_DEFAULT = "default_package"
        const val KEY_ALWAYS_ASK = "always_ask"
        const val KEY_SHOW_OTHERS = "show_other_apps"
        const val KEY_CAMERA_BLOCKED = "camera_blocked"
    }
}
