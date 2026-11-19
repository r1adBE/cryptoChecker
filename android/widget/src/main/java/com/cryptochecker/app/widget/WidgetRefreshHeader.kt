package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.domain.widget.ListWidgetHeader
import com.cryptochecker.app.domain.widget.WidgetHeaderStamp
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.domain.widget.WidgetTextFit
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.resources.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Rechter Teil der Kopfzeile im Puls- und im Portfolio-Widget, genau wie im Listen-Widget: der Stand («Uhrzeit · Dauer»,
 * eng nur die Uhrzeit; veraltet bzw. ohne Netz mit Hinweis) und der Knopf «Aktualisieren»; während des Ladens steht an
 * seiner Stelle ein Kreisel (`busy`), danach in der 15-s-Sperre ein Countdown ([Lock], [WidgetRefreshState]). Ids je
 * Layout ([Ids]), Breiten aus widget_pulse.xml bzw. widget_portfolio_header.xml. Wie iOS `WidgetHeaderStatus`.
 */
internal object WidgetRefreshHeader {

    /** Ids der Views einer Kopfzeile. */
    data class Ids(val status: Int, val refresh: Int, val busy: Int, val countdown: Int)

    /** Widget mit dieser Kopfzeile: eigener Stand und eigene Sperre; [key] für die Einstellungen. */
    enum class Kind(val key: String, val ids: Ids, val unlock: WidgetRefreshUnlock.Target) {
        PULSE(
            "pulse",
            Ids(R.id.pulse_status, R.id.pulse_refresh, R.id.pulse_refresh_busy, R.id.pulse_refresh_countdown),
            WidgetRefreshUnlock.Target(
                PulseWidgetProvider::class.java,
                PulseWidgetProvider.ACTION_REFRESH_HOLD,
                PulseWidgetProvider.ACTION_REFRESH_UNLOCKED,
                -7_015,
            ),
        ),
        PORTFOLIO(
            "portfolio",
            Ids(R.id.portfolio_status, R.id.portfolio_refresh, R.id.portfolio_refresh_busy, R.id.portfolio_refresh_countdown),
            WidgetRefreshUnlock.Target(
                PortfolioWidgetProvider::class.java,
                PortfolioWidgetProvider.ACTION_REFRESH_HOLD,
                PortfolioWidgetProvider.ACTION_REFRESH_UNLOCKED,
                -7_016,
            ),
        ),
    }

    /** Sperre beim Zeichnen: Ende ([until], 0 = keine), Restzeit für den Countdown (0 = Knopf) und Sekunden (Screenreader). */
    data class Lock(val until: Long = 0L, val millis: Long = 0L, val seconds: Int = 0)

    /** Feste Breiten (dp): Rand 2 × 12, Logo 16 + 4, Abstand vor dem Stand 4, Knopf 40 − 10 (negativer Rand). */
    const val FIXED_DP = 2 * 12f + 16f + 4f + 4f + 40f - 10f

    /** Schrift des Stands (sp, wie textSize in den Layouts). */
    const val STATUS_SP = 12f

    /** Zuletzt geplantes Ende je Widget-Art: das Neuzeichnen am Ende nur einmal je Sperre planen. */
    private val unlockScheduled = ConcurrentHashMap<Kind, Long>()

    /**
     * Texte für den Stand, lang → kurz, wie im Listen-Widget ([ListWidgetHeader]): «06:42:15 · 840 ms» / «06:42:15»;
     * veraltet «veraltet · …» / «veraltet»; ohne Netz «Keine Verbindung · Stand …» / «Keine Verbindung». Ohne Zeit (0) leer.
     * @param durationMillis Dauer der Aktualisierung zu diesem Stand ([WidgetHeaderStamp.durationFor]); 0 = ohne
     */
    fun candidates(context: Context, time: Long, durationMillis: Long, online: Boolean, outdated: Boolean): List<String> =
        when (WidgetStatusRule.kind(online, outdated, time)) {
            WidgetStatusRule.Kind.OFFLINE -> listOf(WidgetOffline.label(context, time), WidgetOffline.short(context))
            WidgetStatusRule.Kind.OFFLINE_NO_TIME -> listOf(WidgetOffline.short(context))
            WidgetStatusRule.Kind.OUTDATED ->
                listOf(WidgetOutdated.label(context, time), context.getString(R.string.widget_outdated))
            WidgetStatusRule.Kind.TIME -> if (time > 0L) {
                val clock = PriceFormat.time(time)
                listOf(ListWidgetHeader.statusText(clock, PriceFormat.duration(durationMillis)), clock).distinct()
            } else {
                emptyList()
            }
        }

    /**
     * Sperre für das Zeichnen jetzt ([now]): 15 s nach der letzten Aktualisierung aus dem Widget bzw. bis zum Ende, das
     * ein Tipp gespeichert hat — nur bei eingeschaltetem Bildschirm als Countdown (wie das Listen-Widget). Plant das
     * Neuzeichnen am Ende ([WidgetRefreshUnlock]).
     */
    fun lock(context: Context, prefs: WidgetPrefs, kind: Kind, now: Long = System.currentTimeMillis()): Lock {
        val until = WidgetRefreshState.blockedUntil(
            storedUntil = prefs.headerBlockedUntil(kind),
            lastFinishedAt = prefs.headerStamp(kind).finishedAt,
            runningSince = 0L,
            now = now,
        )
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val millis = WidgetRefreshState.countdownMillis(WidgetRefreshState.remainingMillis(until, now), screenOn)
        if (millis <= 0L) return Lock()
        if (unlockScheduled.put(kind, until) != until) {
            WidgetRefreshUnlock.schedule(context, until, now, kind.unlock)
            WidgetRefreshUnlock.hold(context, until, kind.unlock)
        }
        return Lock(until, millis, WidgetRefreshState.remainingSeconds(until, now))
    }

    /**
     * Tipp auf «Aktualisieren» mit derselben 15-s-Sperre wie im Listen-Widget ([WidgetRefreshState.onTap]): ohne Netz
     * nur neu zeichnen ([redraw]); läuft es schon, nichts; eben erst aktualisiert, Countdown zeichnen (weitere Tipps auf
     * ihn tun nichts); sonst [start].
     */
    suspend fun onTap(
        prefs: WidgetPrefs,
        kind: Kind,
        online: Boolean,
        running: Boolean,
        start: suspend () -> Unit,
        redraw: suspend () -> Unit,
    ) {
        val tap = WidgetRefreshState.onTap(online, running, prefs.headerStamp(kind).finishedAt, System.currentTimeMillis())
        when (tap) {
            WidgetRefreshState.Tap.Offline -> redraw()
            WidgetRefreshState.Tap.Start -> start()
            WidgetRefreshState.Tap.Running -> Unit
            is WidgetRefreshState.Tap.Blocked -> {
                if (prefs.headerBlockedUntil(kind) == tap.until) return
                prefs.setHeaderBlockedUntil(kind, tap.until)
                redraw()
            }
        }
    }

    /** Aktualisierung aus dem Widget fertig: Stand [dataAt] mit der Dauer seit [startedAt] merken (Kopfzeile, Sperre). */
    fun finished(prefs: WidgetPrefs, kind: Kind, dataAt: Long, startedAt: Long, now: Long = System.currentTimeMillis()) {
        prefs.saveHeaderStamp(kind, WidgetHeaderStamp.finished(dataAt, startedAt, now))
    }

    /**
     * Stand und Knopf setzen. Der Stand ist der erste der [candidates], der neben den Titel passt ([widthDp] Breite des
     * Widgets, [titleDp] Breite des Titels); sonst keiner — der Titel bleibt immer. Rechts der Kreisel ([busy]), sonst in
     * der Sperre der Countdown ([lock]), sonst der Knopf.
     * @return gezeigter Stand (leer = keiner)
     */
    fun bind(
        context: Context,
        toolkit: WidgetToolkit,
        views: RemoteViews,
        ids: Ids,
        candidates: List<String>,
        widthDp: Int,
        titleDp: Float,
        colors: WidgetColors,
        refresh: PendingIntent,
        busy: Boolean = false,
        lock: Lock = Lock(),
    ): String {
        val available = if (widthDp > 0) widthDp - FIXED_DP - titleDp - WidgetTextFit.SAFETY_DP else null
        val text = candidates.firstOrNull {
            available == null ||
                toolkit.textWidthDp(it, STATUS_SP, bold = false, tabular = true) <= available
        }
            .orEmpty()
        views.setTextViewText(ids.status, text)
        views.setViewVisibility(ids.status, if (text.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextColor(ids.status, colors.secondaryTextColor)
        // Knopf neutral wie im Listen-Widget: weiss im Dunkeln, schwarz im Hellen — nicht in der Akzentfarbe
        views.setInt(ids.refresh, "setColorFilter", colors.textColor)
        val countdown = !busy && lock.millis > 0L
        views.setViewVisibility(ids.refresh, if (busy || countdown) View.GONE else View.VISIBLE)
        views.setViewVisibility(ids.busy, if (busy) View.VISIBLE else View.GONE)
        views.setViewVisibility(ids.countdown, if (countdown) View.VISIBLE else View.GONE)
        views.setOnClickPendingIntent(ids.refresh, refresh)
        if (countdown) {
            // Zählt der Launcher selbst herunter; am Ende zeichnet [WidgetRefreshUnlock] neu (Symbol zurück)
            views.setChronometer(
                ids.countdown,
                WidgetRefreshState.chronometerBase(SystemClock.elapsedRealtime(), lock.millis),
                null,
                true,
            )
            views.setChronometerCountDown(ids.countdown, true)
            views.setTextColor(ids.countdown, colors.secondaryTextColor)
            // Screenreader: «Aktualisieren – wieder möglich in 12 s» statt der laufenden Ziffern
            views.setContentDescription(
                ids.countdown,
                context.getString(R.string.widget_refresh_blocked_a11y, lock.seconds),
            )
            views.setOnClickPendingIntent(ids.countdown, refresh)
        } else {
            // Angehalten: zählt verborgen nicht weiter
            views.setChronometer(ids.countdown, SystemClock.elapsedRealtime(), null, false)
        }
        return text
    }

    /** Platz, den Stand und Knopf neben dem Titel brauchen (dp), für die Wahl der Titelgrösse. */
    fun usedDp(toolkit: WidgetToolkit, status: String): Float =
        (if (status.isEmpty()) 0f else 4f + toolkit.textWidthDp(status, STATUS_SP, bold = false, tabular = true)) + 40f - 10f

    /** Tipp auf «Aktualisieren»: Broadcast an [provider] mit [action] (eigener Request-Code je Widget). */
    fun intent(context: Context, provider: Class<*>, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, provider).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
