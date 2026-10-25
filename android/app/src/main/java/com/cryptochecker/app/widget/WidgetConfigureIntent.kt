package com.cryptochecker.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider

/**
 * Widget-Id aus dem Intent der Einrichten-Activity, wenn sie zu einem eigenen Widget des Anbieters [provider] gehört
 * ([WidgetConfigureGuard]); sonst null — die Activity schliesst sich dann ohne Ergebnis.
 */
internal fun Activity.ownWidgetId(provider: Class<out AppWidgetProvider>): Int? {
    val invalid = AppWidgetManager.INVALID_APPWIDGET_ID
    val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, invalid) ?: invalid
    if (widgetId == invalid) return null
    val owner = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider }.getOrNull()
    val ok = WidgetConfigureGuard.accepts(
        widgetId, invalid, owner?.packageName, owner?.className, packageName, provider.name,
    )
    return widgetId.takeIf { ok }
}
