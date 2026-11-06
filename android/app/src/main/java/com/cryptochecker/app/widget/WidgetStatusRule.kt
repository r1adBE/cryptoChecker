package com.cryptochecker.app.widget

/**
 * Was die Zeit-Zeile eines Widgets zeigt (Kopf der Liste, Fusszeile von Einzel-, Portfolio- und Puls-Widget):
 * ohne Internet «Keine Verbindung · Stand 14:05» (Zeit der letzten erfolgreichen Aktualisierung, ohne bekannte Zeit
 * nur «Keine Verbindung»), sonst bei altem Stand «veraltet · 06:42», sonst die Uhrzeit. Vorrang: offline > veraltet >
 * Uhrzeit — ohne Netz hilft «veraltet» nicht weiter, «Keine Verbindung» sagt, warum nichts Neues kommt.
 * Reine Regel ohne Android; gemeinsame Testfälle `testdata/parity/widget_status.json`, Swift-Spiegel
 * WidgetStatusRule.swift.
 */
object WidgetStatusRule {

    enum class Kind { OFFLINE, OFFLINE_NO_TIME, OUTDATED, TIME }

    /**
     * @param online Internet laut System (Android: bestätigtes Netz, [com.cryptochecker.app.util.ConnectivityMonitor])
     * @param outdated gezeigter Stand ist veraltet (Regel der jeweiligen Zeile)
     * @param time Zeit der letzten erfolgreichen Aktualisierung (ms); 0 oder kleiner = keine
     */
    fun kind(online: Boolean, outdated: Boolean, time: Long): Kind = when {
        !online && time > 0 -> Kind.OFFLINE
        !online -> Kind.OFFLINE_NO_TIME
        outdated -> Kind.OUTDATED
        else -> Kind.TIME
    }
}
