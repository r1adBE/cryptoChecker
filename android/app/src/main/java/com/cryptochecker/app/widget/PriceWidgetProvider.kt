package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cryptochecker.app.util.launchAsync
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Watchlist-Widget für den Startbildschirm. */
@AndroidEntryPoint
class PriceWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var scheduler: PriceUpdateScheduler

    override fun onReceive(context: Context, intent: Intent) {
        // Ruft die Injektion auf und verteilt an onUpdate/onDeleted.
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> scheduler.refreshNow()
            // Ein angezeigter Stand ist inzwischen veraltet: ohne Netz neu zeichnen («veraltet · 06:42»)
            ACTION_OUTDATED_CHECK -> {
                launchAsync("Veraltete Widgets neu zeichnen") {
                    widgetUpdater.redrawOutdated()
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        launchAsync("Listen-Widget aktualisieren") {
            widgetUpdater.update(appWidgetIds)
        }
    }

    /** Grösse geändert: Kopfzeile (Titel, Uhrzeit · Dauer) für die neue Breite wählen. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        launchAsync("Listen-Widget neu zeichnen") {
            widgetUpdater.update(intArrayOf(appWidgetId))
        }
    }

    /** Erstes Widget dieser Art platziert bzw. letztes entfernt: Bedarf der Hintergrund-Aktualisierung neu prüfen. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduler.onWidgetsChanged()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        scheduler.onWidgetsChanged()
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
