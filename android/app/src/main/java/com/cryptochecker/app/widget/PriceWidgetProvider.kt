package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
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

    @Inject lateinit var refreshStats: RefreshStats

    @Inject lateinit var priceRefresher: PriceRefresher

    override fun onReceive(context: Context, intent: Intent) {
        // Ruft die Injektion auf und verteilt an onUpdate/onDeleted.
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> onRefreshTap()
            // Sperre vorbei: Symbol statt Countdown
            ACTION_REFRESH_UNLOCKED -> {
                launchAsync("Listen-Widgets nach der Sperre neu zeichnen") {
                    widgetUpdater.update(widgetUpdater.widgetIds())
                }
            }
            // Ein angezeigter Stand ist inzwischen veraltet: ohne Netz neu zeichnen («veraltet · 06:42»)
            ACTION_OUTDATED_CHECK -> {
                launchAsync("Veraltete Widgets neu zeichnen") {
                    widgetUpdater.redrawOutdated()
                }
            }
        }
    }

    /**
     * Knopf «Aktualisieren»: gleiche 15-s-Sperre wie die App ([WidgetRefreshState]). Läuft schon eine
     * vollständige Aktualisierung, nichts tun (eine wartende des Widgets hält refreshNow mit KEEP fest).
     * Eben erst fertig: nichts holen; die Listen-Widgets zeigen den Countdown (gewöhnlich schon seit dem
     * Zeichnen nach der Aktualisierung), das Neuzeichnen am Ende plant [ListWidgetRenderer].
     */
    private fun onRefreshTap() {
        val tap = WidgetRefreshState.onTap(
            running = priceRefresher.fullRefreshRunning.value,
            lastFinishedAt = refreshStats.lastFullRefresh(),
            now = System.currentTimeMillis(),
        )
        when (tap) {
            WidgetRefreshState.Tap.Start -> scheduler.refreshNow()
            WidgetRefreshState.Tap.Running -> Unit
            is WidgetRefreshState.Tap.Blocked -> {
                // Erster Tipp in dieser Sperre: sicherheitshalber neu zeichnen (etwa wenn das Zeichnen nach der
                // Aktualisierung ausblieb); weitere Tipps auf den Countdown tun nichts
                if (widgetPrefs.refreshBlockedUntil == tap.until) return
                widgetPrefs.refreshBlockedUntil = tap.until
                launchAsync("Listen-Widgets mit Countdown zeichnen") {
                    widgetUpdater.update(widgetUpdater.widgetIds())
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
        /** Wecker aus [WidgetRefreshUnlock.schedule]: Sperre des Knopfs vorbei, Symbol wieder zeigen. */
        const val ACTION_REFRESH_UNLOCKED = "com.cryptochecker.app.action.WIDGET_REFRESH_UNLOCKED"
        /** Wecker aus [WidgetOutdated.schedule]: Widgets mit inzwischen veraltetem Stand neu zeichnen. */
        const val ACTION_OUTDATED_CHECK = "com.cryptochecker.app.action.WIDGET_OUTDATED_CHECK"
    }
}
