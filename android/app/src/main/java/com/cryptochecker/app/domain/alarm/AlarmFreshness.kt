package com.cryptochecker.app.domain.alarm

/**
 * Darf ein frisch abgefragter Kurs Alarme auslösen? Liefert die Börse eine eigene Zeit des Kurses
 * (`exchangeTime`, z. B. letzter Handel) und liegt sie mehr als [EXCHANGE_STALE_MILLIS] zurück, ist der
 * Kurs alt, auch wenn der Abruf eben erst war (Handel ruht, Börse liefert einen stehengebliebenen Wert):
 * dann keine Alarme in diesem Durchlauf — sie bleiben scharf und melden beim nächsten frischen Kurs.
 * Das Alter zeigt die Zeile ohnehin («veraltet», [com.cryptochecker.app.domain.watch.PriceFreshness]).
 * Ohne Zeit der Börse ändert sich nichts. Reine Logik, wie `AlarmFreshness.swift`.
 */
object AlarmFreshness {
    /** Ab diesem Alter laut Börse lösen Alarme nicht aus: 2 Stunden. */
    const val EXCHANGE_STALE_MILLIS = 2 * 60 * 60_000L

    /** Kurs laut Börse zu alt für Alarme? Ohne (≤ 0) oder mit künftiger Zeit nie. */
    fun exchangeTimeTooOld(exchangeTime: Long?, now: Long): Boolean =
        exchangeTime != null && exchangeTime > 0 && now - exchangeTime > EXCHANGE_STALE_MILLIS
}
