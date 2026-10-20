package com.cryptochecker.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.work.ExactCheckScheduler
import timber.log.Timber

/**
 * Ende der 15-s-Sperre im Listen-Widget: Den Countdown zählt der Launcher selbst herunter (Chronometer),
 * neu gezeichnet wird genau einmal, wenn die Sperre endet — dann kommt das Symbol zurück. Der Wecker ist
 * die Absicherung, falls der Prozess bis dahin beendet oder eingefroren ist; pünktlich (exakt) nur mit
 * «Wecker und Erinnerungen», sonst ungefähr, ohne zu wecken (sichtbar ist das Widget ohnehin nur bei
 * eingeschaltetem Bildschirm).
 */
object WidgetRefreshUnlock {

    /** Wecker auf [blockedUntil] (Wanduhr, ms) stellen; ersetzt einen früheren. */
    fun schedule(context: Context, blockedUntil: Long, now: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PriceWidgetProvider::class.java).setAction(PriceWidgetProvider.ACTION_REFRESH_UNLOCKED)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Seit dem Start gemessen: unabhängig von einer verstellten Uhr
        val at = SystemClock.elapsedRealtime() + (blockedUntil - now).coerceAtLeast(0) +
            WidgetRefreshState.UNLOCK_SLACK_MILLIS
        runCatching {
            if (!ExactCheckScheduler.canScheduleExact(context)) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, at, pending)
                return@runCatching
            }
            try {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME, at, pending)
            } catch (e: SecurityException) {
                // Berechtigung eben entzogen: ungefähr
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, at, pending)
            }
        }.onFailure { Timber.w(it, "Wecker für das Ende der Widget-Sperre nicht gestellt") }
    }

    /** Eigener Request-Code (Knöpfe: Widget-Id, veraltet: -7013). */
    private const val REQUEST_CODE = -7_014
}
