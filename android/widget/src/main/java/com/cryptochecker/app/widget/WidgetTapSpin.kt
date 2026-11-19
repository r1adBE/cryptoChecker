package com.cryptochecker.app.widget

import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews

/**
 * Knopf «Aktualisieren» dreht sich, bis geladen ist (Inhaber: «Bis es lädt soll der Knopf sich drehen»), in allen drei
 * Widgets gleich: nach dem Tipp ([startedAt]) bis zum Ende des Ladens ([doneAt]), mindestens [ACK_MILLIS] (wie beim
 * Ziehen in der App und auf der Uhr), höchstens [MAX_MILLIS] (falls das Ende ausbleibt). Danach der Knopf bzw. der
 * 15-s-Countdown. Je Widget-Art ein eigener Stand ([Key], [WidgetPrefs.tapSpinStartedAt]).
 */
object WidgetTapSpin {

    /** Widget-Art (Schlüssel in den Einstellungen). */
    enum class Key(val key: String) { LIST("list"), PULSE("pulse"), PORTFOLIO("portfolio") }

    /** Mindestens so lange drehen (ms) — wie [com.cryptochecker.app.domain.util.LoadProgress.PULL_ACK_MILLIS]. */
    const val ACK_MILLIS = com.cryptochecker.app.domain.util.LoadProgress.PULL_ACK_MILLIS

    /** Höchstens so lange (ms), falls das Ende des Ladens nie gemeldet wird (Fehler, Zeitgrenze). */
    const val MAX_MILLIS = 30_000L

    /**
     * Dreht der Knopf jetzt ([now])? Nach einem Tipp ([startedAt] > 0), solange das Laden nicht fertig ist ([doneAt] vor
     * dem Tipp) oder noch keine [ACK_MILLIS] vergangen sind — nie länger als [MAX_MILLIS].
     */
    fun busy(startedAt: Long, doneAt: Long, now: Long): Boolean {
        if (startedAt <= 0L) return false
        val elapsed = now - startedAt
        if (elapsed < 0L || elapsed >= MAX_MILLIS) return false
        return doneAt < startedAt || elapsed < ACK_MILLIS
    }

    /** Wie [busy] mit dem gespeicherten Tipp und Ende des Ladens dieser Widget-Art (Puls, Portfolio). */
    internal fun busy(prefs: WidgetPrefs, key: Key, now: Long = System.currentTimeMillis()): Boolean =
        busy(prefs.tapSpinStartedAt(key), prefs.tapSpinDoneAt(key), now)

    /** Wie lange nach einem fertigen Laden noch zu drehen ist (ms), damit es mindestens [ACK_MILLIS] sind. */
    fun ackLeft(startedAt: Long, now: Long): Long = (ACK_MILLIS - (now - startedAt)).coerceIn(0L, ACK_MILLIS)

    /**
     * Drehendes Symbol ([spin], ProgressBar mit ic_widget_refresh_spin) statt Knopf ([refresh]) und Countdown
     * ([countdown]); sonst aus. Farbe wie der Knopf ([color], ab Android 12; davor die Farbe des Symbols).
     */
    fun bind(views: RemoteViews, busy: Boolean, refresh: Int, spin: Int, countdown: Int, color: Int) {
        views.setViewVisibility(spin, if (busy) View.VISIBLE else View.GONE)
        if (!busy) return
        views.setViewVisibility(refresh, View.GONE)
        views.setViewVisibility(countdown, View.GONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(spin, "setIndeterminateTintList", ColorStateList.valueOf(color))
        }
    }
}
