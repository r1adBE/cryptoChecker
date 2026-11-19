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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Portfolio-Widget: Gesamtwert in der Umrechnungswährung mit Veränderung über 24 h; je nach
 * Grösse dazu Wertverlauf und die drei grössten Positionen (siehe [WidgetUpdater.updatePortfolio]).
 */
@AndroidEntryPoint
class PortfolioWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var snapshotUpdater: PortfolioSnapshotUpdater

    @Inject lateinit var scheduler: WidgetScheduler

    @Inject lateinit var connectivity: WidgetConnectivity

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val kind = WidgetRefreshHeader.Kind.PORTFOLIO
        when (intent.action) {
            ACTION_REFRESH -> launchAsync("Portfolio-Widget per Tipp aktualisieren") {
                WidgetRefreshHeader.onTap(
                    prefs = widgetPrefs,
                    kind = kind,
                    online = connectivity.online(),
                    running = widgetUpdater.portfolioRefreshing,
                    start = { refreshByTap() },
                    redraw = { widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds()) },
                )
            }
            // Sperre vorbei: Symbol statt Countdown
            ACTION_REFRESH_UNLOCKED -> launchAsync("Portfolio-Widgets nach der Sperre neu zeichnen") {
                widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
            }
            ACTION_REFRESH_HOLD -> {
                val blockedUntil = intent.getLongExtra(PriceWidgetProvider.EXTRA_BLOCKED_UNTIL, 0L)
                launchAsync("Portfolio-Widgets am Ende der Sperre neu zeichnen") {
                    WidgetRefreshUnlock.onHold(context, blockedUntil, kind.unlock) {
                        widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
                    }
                }
            }
        }
    }

    /** Knopf «Aktualisieren»: frisch rechnen (Kurse), neu zeichnen — auch bei Fehler (dann Countdown oder Knopf). */
    private suspend fun refreshByTap() {
        val ids = widgetUpdater.portfolioWidgetIds()
        widgetUpdater.setPortfolioRefreshing(ids, on = true)
        try {
            com.cryptochecker.app.domain.work.countedRun {
                withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { snapshotUpdater.refresh() }
            }
        } finally {
            // Auch bei Fehler oder Zeitgrenze: Stand neu
            withContext(NonCancellable) { widgetUpdater.setPortfolioRefreshing(ids, on = false) }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Abruf vom System angestossen: zählt für «Akku & Aktivität» als Hintergrund-Lauf
        launchAsync("Portfolio-Widget aktualisieren") {
            com.cryptochecker.app.domain.work.countedRun {
                // Sofort mit der letzten Aufnahme zeichnen, dann frisch berechnen
                widgetUpdater.updatePortfolio(appWidgetIds)
                // goAsync erlaubt nur rund 10 s
                withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { snapshotUpdater.refresh() }
            }
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

    companion object {
        /** Tipp auf «Aktualisieren» in der Kopfzeile ([WidgetRefreshHeader]). */
        const val ACTION_REFRESH = "com.cryptochecker.app.action.PORTFOLIO_WIDGET_REFRESH"

        /** Wecker aus [WidgetRefreshUnlock.schedule]: Sperre vorbei, Symbol wieder zeigen. */
        const val ACTION_REFRESH_UNLOCKED = "com.cryptochecker.app.action.PORTFOLIO_WIDGET_REFRESH_UNLOCKED"

        /** Empfang aus [WidgetRefreshUnlock.hold]: bis zum Ende der Sperre warten, dann Symbol statt Countdown. */
        const val ACTION_REFRESH_HOLD = "com.cryptochecker.app.action.PORTFOLIO_WIDGET_REFRESH_HOLD"

        private const val REFRESH_TIMEOUT_MILLIS = ReceiverAsync.FETCH_MILLIS
    }
}
