package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cryptochecker.app.util.ReceiverAsync
import com.cryptochecker.app.util.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widget «Was gerade auffällt» (Crypto Pulse): Schlagzeile, Leitsatz und Coin-Chips wie die
 * Karte im Markt-Tab (siehe [WidgetUpdater.updatePulse]). Daten aus dem Zwischenspeicher des
 * Markt-Tabs (5 Min.), sonst frisch über dieselbe Quelle.
 */
@AndroidEntryPoint
class PulseWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var scheduler: WidgetScheduler

    @Inject lateinit var connectivity: WidgetConnectivity

    override fun onReceive(context: Context, intent: Intent) {
        // Ruft die Injektion auf und verteilt an onUpdate/onDeleted.
        super.onReceive(context, intent)
        // Ein Satz von «Kurz gesagt» stünde jetzt auf veralteten Daten: ohne Netz neu zeichnen
        if (intent.action == ACTION_PLAIN_EXPIRED) {
            launchAsync("Pulse-Widget «Kurz gesagt» neu zeichnen") {
                widgetUpdater.updatePulse(widgetUpdater.pulseWidgetIds(), fetch = false)
            }
        }
        // Knopf «Aktualisieren» in der Kopfzeile: alle Pulse-Widgets (gleiche Daten), Kreisel bis zum Neuzeichnen;
        // danach 15 s Countdown wie im Listen-Widget ([WidgetRefreshHeader.onTap])
        val kind = WidgetRefreshHeader.Kind.PULSE
        when (intent.action) {
            ACTION_REFRESH -> launchAsync("Pulse-Widget per Tipp aktualisieren") {
                val ids = widgetUpdater.pulseWidgetIds()
                WidgetRefreshHeader.onTap(
                    prefs = widgetPrefs,
                    kind = kind,
                    online = connectivity.online(),
                    running = widgetUpdater.pulseRefreshing,
                    start = {
                        withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { widgetUpdater.refreshPulseByTap(ids) }
                            ?: widgetUpdater.updatePulse(ids, fetch = false)
                    },
                    redraw = { widgetUpdater.updatePulse(ids, fetch = false) },
                )
            }
            // Sperre vorbei: Symbol statt Countdown
            ACTION_REFRESH_UNLOCKED -> launchAsync("Pulse-Widgets nach der Sperre neu zeichnen") {
                widgetUpdater.updatePulse(widgetUpdater.pulseWidgetIds(), fetch = false)
            }
            ACTION_REFRESH_HOLD -> {
                val blockedUntil = intent.getLongExtra(PriceWidgetProvider.EXTRA_BLOCKED_UNTIL, 0L)
                launchAsync("Pulse-Widgets am Ende der Sperre neu zeichnen") {
                    WidgetRefreshUnlock.onHold(context, blockedUntil, kind.unlock) {
                        widgetUpdater.updatePulse(widgetUpdater.pulseWidgetIds(), fetch = false)
                    }
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Abruf vom System angestossen: zählt für «Akku & Aktivität» als Hintergrund-Lauf
        launchAsync("Pulse-Widget aktualisieren") {
            com.cryptochecker.app.domain.work.countedRun {
                // goAsync erlaubt nur rund 10 s: sofort aus dem Zwischenspeicher, dann frisch
                val done = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { widgetUpdater.updatePulse(appWidgetIds, fetch = true) }
                // Zeitgrenze erreicht (ohne Zwischenspeicher bliebe das Widget sonst leer):
                // Zwischenspeicher oder ruhig «nicht verfügbar» zeigen, ohne Netz
                if (done == null) widgetUpdater.updatePulse(appWidgetIds, fetch = false)
            }
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
        private const val REFRESH_TIMEOUT_MILLIS = ReceiverAsync.FETCH_MILLIS

        /** Tipp auf «Aktualisieren» in der Kopfzeile ([WidgetRefreshHeader]). */
        const val ACTION_REFRESH = "com.cryptochecker.app.action.PULSE_WIDGET_REFRESH"

        /** Wecker aus [WidgetRefreshUnlock.schedule]: Sperre vorbei, Symbol wieder zeigen. */
        const val ACTION_REFRESH_UNLOCKED = "com.cryptochecker.app.action.PULSE_WIDGET_REFRESH_UNLOCKED"

        /** Empfang aus [WidgetRefreshUnlock.hold]: bis zum Ende der Sperre warten, dann Symbol statt Countdown. */
        const val ACTION_REFRESH_HOLD = "com.cryptochecker.app.action.PULSE_WIDGET_REFRESH_HOLD"

        /** Wecker aus [PulseWidgetRenderer] (veraltender Satz von «Kurz gesagt»). */
        const val ACTION_PLAIN_EXPIRED = "com.cryptochecker.app.action.PULSE_PLAIN_EXPIRED"
    }
}
