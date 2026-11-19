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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/** Watchlist-Widget für den Startbildschirm. */
@AndroidEntryPoint
class PriceWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var scheduler: WidgetScheduler

    @Inject lateinit var refreshStats: RefreshStats

    @Inject lateinit var priceRefresher: PriceRefresher

    @Inject lateinit var connectivity: WidgetConnectivity

    override fun onReceive(context: Context, intent: Intent) {
        // Ruft die Injektion auf und verteilt an onUpdate/onDeleted.
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> onRefreshTap(context)
            // Sperre vorbei: Symbol statt Countdown
            ACTION_REFRESH_UNLOCKED -> {
                launchAsync("Listen-Widgets nach der Sperre neu zeichnen") {
                    widgetUpdater.update(widgetUpdater.widgetIds())
                }
            }
            // Bis zum Ende der Sperre warten, dann neu zeichnen (oder an den nächsten Empfang weiterreichen)
            ACTION_REFRESH_HOLD -> {
                val blockedUntil = intent.getLongExtra(EXTRA_BLOCKED_UNTIL, 0L)
                launchAsync("Listen-Widgets am Ende der Sperre neu zeichnen") {
                    val step = WidgetRefreshUnlock.holdStep(blockedUntil, System.currentTimeMillis())
                    delay(step.waitMillis)
                    if (step.redraw) {
                        widgetUpdater.update(widgetUpdater.widgetIds())
                    } else {
                        WidgetRefreshUnlock.hold(context, blockedUntil)
                    }
                }
            }
            // Drehender Knopf: auf das Ende der Aktualisierung warten, dann neu zeichnen ([spinHold])
            ACTION_SPIN_HOLD -> {
                val startedAt = intent.getLongExtra(EXTRA_SPIN_STARTED_AT, 0L)
                launchAsync("Listen-Widgets nach dem Laden neu zeichnen") { spinHold(context, startedAt) }
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
     * Ohne Internet: nichts holen und kein Countdown, nur neu zeichnen («Keine Verbindung · Stand 14:05»); kommt das
     * Netz zurück, holt [com.cryptochecker.app.widget.WidgetReconnectWorker] die Kurse ([WidgetConnectivity]).
     */
    private fun onRefreshTap(context: Context) {
        val tap = WidgetRefreshState.onTap(
            online = connectivity.online(),
            running = priceRefresher.fullRefreshRunning.value,
            lastFinishedAt = refreshStats.lastFullRefresh(),
            now = System.currentTimeMillis(),
        )
        when (tap) {
            // Ohne Netz: nur neu zeichnen («Keine Verbindung · Stand 14:05»), nichts holen, kein Countdown
            WidgetRefreshState.Tap.Offline ->
                launchAsync("Listen-Widgets ohne Netz zeichnen") { widgetUpdater.update(widgetUpdater.widgetIds()) }
            WidgetRefreshState.Tap.Start -> {
                // Knopf dreht sich, bis die Aktualisierung fertig ist ([WidgetTapSpin]); danach neu zeichnen
                val startedAt = System.currentTimeMillis()
                widgetPrefs.setTapSpinStartedAt(WidgetTapSpin.Key.LIST, startedAt)
                scheduler.refreshNow()
                launchAsync("Listen-Widgets mit drehendem Knopf zeichnen") {
                    widgetUpdater.update(widgetUpdater.widgetIds())
                    spinHold(context, startedAt)
                }
            }
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

    /**
     * Drehender Knopf nach einem Tipp: in kleinen Schritten warten, bis die Aktualisierung fertig ist (mindestens
     * [WidgetTapSpin.ACK_MILLIS], höchstens [WidgetTapSpin.MAX_MILLIS]), dann neu zeichnen. Länger als ein Empfang
     * dauern darf: an den nächsten Empfang weiterreichen ([ACTION_SPIN_HOLD]).
     */
    private suspend fun spinHold(context: Context, startedAt: Long) {
        val until = System.currentTimeMillis() + WidgetRefreshUnlock.HOP_MILLIS
        while (WidgetTapSpin.busy(startedAt, refreshStats.lastFullRefresh(), System.currentTimeMillis())) {
            if (System.currentTimeMillis() >= until) {
                val next = Intent(context, PriceWidgetProvider::class.java)
                    .setAction(ACTION_SPIN_HOLD)
                    .putExtra(EXTRA_SPIN_STARTED_AT, startedAt)
                WidgetRefreshUnlock.send(context, next)
                return
            }
            delay(SPIN_POLL_MILLIS)
        }
        widgetUpdater.update(widgetUpdater.widgetIds())
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

        /** Empfang aus [WidgetRefreshUnlock.hold]: bis zum Ende der Sperre warten, dann Symbol statt Countdown. */
        const val ACTION_REFRESH_HOLD = "com.cryptochecker.app.action.WIDGET_REFRESH_HOLD"

        /** Ende der Sperre (Wanduhr, ms) zu [ACTION_REFRESH_HOLD]. */
        const val EXTRA_BLOCKED_UNTIL = "com.cryptochecker.app.extra.BLOCKED_UNTIL"

        /** Empfang aus [spinHold]: weiter auf das Ende der Aktualisierung warten (drehender Knopf). */
        const val ACTION_SPIN_HOLD = "com.cryptochecker.app.action.WIDGET_REFRESH_SPIN_HOLD"
        const val EXTRA_SPIN_STARTED_AT = "com.cryptochecker.app.extra.SPIN_STARTED_AT"

        /** Abstand der Blicke auf das Ende der Aktualisierung (ms). */
        private const val SPIN_POLL_MILLIS = 250L

        /** Wecker aus [WidgetOutdated.schedule]: Widgets mit inzwischen veraltetem Stand neu zeichnen. */
        const val ACTION_OUTDATED_CHECK = "com.cryptochecker.app.action.WIDGET_OUTDATED_CHECK"
    }
}
