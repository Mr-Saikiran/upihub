package dev.upihub

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/** 1x1 home-screen widget that opens straight into the scanner. */
class ScanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
            setOnClickPendingIntent(R.id.widgetRoot, scanIntent(context))
        }
        manager.updateAppWidget(ids, views)
    }

    companion object {
        private fun scanIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Asks the launcher to place the widget (Android 8+). Returns false
         * when the launcher does not support it, so the caller can explain
         * how to add it by hand.
         */
        fun requestPin(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, ScanWidgetProvider::class.java), null, null)
        }
    }
}
