package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.cryptochecker.app.util.ReceiverAsync
import com.cryptochecker.app.util.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Portfolio-Widget: Gesamtwert in der Umrechnungswährung mit Veränderung über 24 h; je nach
 * Grösse dazu Wertverlauf und die drei grössten Positionen (siehe [WidgetUpdater.updatePortfolio]).
 */
@AndroidEntryPoint
class PortfolioWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var snapshotUpdater: PortfolioSnapshotUpdater

    @Inject lateinit var scheduler: com.cryptochecker.app.work.PriceUpdateScheduler

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync("Portfolio-Widget aktualisieren") {
            // Sofort mit der letzten Aufnahme zeichnen, dann frisch berechnen
            widgetUpdater.updatePortfolio(appWidgetIds)
            // goAsync erlaubt nur rund 10 s
            withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { snapshotUpdater.refresh() }
        }
    }

    /** Grösse geändert: aus der letzten Aufnahme in der neuen Stufe zeichnen (ohne Netz). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        launchAsync("Portfolio-Widget neu zeichnen") {
            widgetUpdater.updatePortfolio(intArrayOf(appWidgetId))
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
        widgetUpdater.forgetPortfolioWidgets(appWidgetIds)
    }

    private companion object {
        const val REFRESH_TIMEOUT_MILLIS = ReceiverAsync.FETCH_MILLIS
    }
}
