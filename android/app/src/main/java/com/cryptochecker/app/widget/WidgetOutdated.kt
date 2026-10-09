package com.cryptochecker.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.NOT_TRADED_MARKER
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.settings.AppSettings
import timber.log.Timber

/**
 * Veraltete Kurse in den Widgets: Statt nur der Uhrzeit steht «veraltet · 06:42» (das Wort,
 * nicht nur eine Farbe), der Screenreader sagt «veraltet, letzte Aktualisierung 06:42».
 * Regel wie in der Merkliste ([OutdatedRule]). Da Widgets nur nach einer Aktualisierung neu
 * gezeichnet werden, weckt ein (nicht weckender) Wecker sie zum Zeitpunkt, an dem der
 * nächste Kurs veraltet — sonst sähe der Kurs von gestern frisch aus.
 */
object WidgetOutdated {

    /**
     * Grenze aus den Einstellungen (3 × Intervall, mind. 15 Min.). [live] = Live-Modus aktiv
     * (Live-Stream): dann die strengere Live-Grenze von [OutdatedRule] (2 Min.). Widgets sind nur
     * sichtbar, wenn die App nicht vorne ist — ohne Stream gilt daher die Hintergrund-Regel.
     */
    fun afterMillis(settings: AppSettings, live: Boolean = false): Long =
        OutdatedRule.afterMillis(settings.liveService, settings.liveIntervalSeconds, settings.backgroundIntervalMinutes, live)

    /** Kurs eines Paares veraltet? «Nicht mehr gehandelt» ist ein Zustand und zählt nicht. */
    fun isOutdated(lastUpdate: Long, lastError: String?, now: Long, afterMillis: Long): Boolean =
        lastError != NOT_TRADED_MARKER && OutdatedRule.isOutdated(lastUpdate, now, afterMillis)

    /** «06:42» (heute) bzw. «5. Okt., 06:42» — bei altem Stand zählt auch der Tag. */
    fun stamp(context: Context, time: Long): String {
        val flags = if (DateUtils.isToday(time)) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        return DateUtils.formatDateTime(context, time, flags)
    }

    /** «veraltet · 06:42» */
    fun label(context: Context, time: Long): String =
        context.getString(R.string.widget_outdated_time, stamp(context, time))

    /** «veraltet, letzte Aktualisierung 06:42» */
    fun spoken(context: Context, time: Long): String =
        context.getString(R.string.a11y_stale, stamp(context, time))

    /**
     * Wecker auf den nächsten Wechsel zu «veraltet» stellen (oder löschen, wenn keiner mehr
     * kommt). RTC ohne Wecken: fällig wird er, sobald das Gerät ohnehin wach ist — dann, wenn
     * jemand auf den Startbildschirm schaut. Kein exakter Wecker, keine Berechtigung nötig.
     */
    fun schedule(context: Context, times: Iterable<Long>, afterMillis: Long, now: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PriceWidgetProvider::class.java).setAction(PriceWidgetProvider.ACTION_OUTDATED_CHECK)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = OutdatedRule.nextChangeAt(times, now, afterMillis)
        runCatching {
            if (at == null) alarmManager.cancel(pending) else alarmManager.set(AlarmManager.RTC, at, pending)
        }.onFailure { Timber.w(it, "Wecker für veraltete Widget-Kurse nicht gestellt") }
    }

    /** Eigener Request-Code (die Aktualisieren-Knöpfe nutzen die Widget-Id). */
    private const val REQUEST_CODE = -7_013
}
