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
 * Widget «Was gerade auffällt» (Crypto Pulse): Schlagzeile, Leitsatz und Coin-Chips wie die
 * Karte im Markt-Tab (siehe [WidgetUpdater.updatePulse]). Daten aus dem Zwischenspeicher des
 * Markt-Tabs (5 Min.), sonst frisch über dieselbe Quelle.
 */
@AndroidEntryPoint
class PulseWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync("Pulse-Widget aktualisieren") {
            // goAsync erlaubt nur rund 10 s: sofort aus dem Zwischenspeicher, dann frisch
            val done = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { widgetUpdater.updatePulse(appWidgetIds, fetch = true) }
            // Zeitgrenze erreicht (ohne Zwischenspeicher bliebe das Widget sonst leer):
            // Zwischenspeicher oder ruhig «nicht verfügbar» zeigen, ohne Netz
            if (done == null) widgetUpdater.updatePulse(appWidgetIds, fetch = false)
        }
    }

    /** Grösse geändert: neu zeichnen (ein oder drei Coin-Chips), ohne Netz. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        launchAsync("Pulse-Widget neu zeichnen") {
            widgetUpdater.updatePulse(intArrayOf(appWidgetId), fetch = false)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetPrefs.remove(it) }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MILLIS = ReceiverAsync.FETCH_MILLIS
    }
}
