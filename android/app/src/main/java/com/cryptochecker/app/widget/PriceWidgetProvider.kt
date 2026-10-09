package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Watchlist-Widget für den Startbildschirm. */
@AndroidEntryPoint
class PriceWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var scheduler: PriceUpdateScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        // Ruft die Injektion auf und verteilt an onUpdate/onDeleted.
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> scheduler.refreshNow()
            // Ein angezeigter Stand ist inzwischen veraltet: ohne Netz neu zeichnen («veraltet · 06:42»)
            ACTION_OUTDATED_CHECK -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        widgetUpdater.redrawOutdated()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                widgetUpdater.update(appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Grösse geändert: Kopfzeile (Titel, Uhrzeit · Dauer) für die neue Breite wählen. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                widgetUpdater.update(intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetPrefs.remove(it) }
    }

    companion object {
        const val ACTION_REFRESH = "com.cryptochecker.app.action.WIDGET_REFRESH"
        /** Wecker aus [WidgetOutdated.schedule]: Widgets mit inzwischen veraltetem Stand neu zeichnen. */
        const val ACTION_OUTDATED_CHECK = "com.cryptochecker.app.action.WIDGET_OUTDATED_CHECK"
    }
}
