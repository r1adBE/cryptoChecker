package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.refresh.ExchangeBackoff

/**
 * Börse pausiert ([ExchangeBackoff]: zu viele Anfragen bzw. wiederholte Zeitüberschreitungen): Die Zeile der Merkliste
 * sagt es statt der normalen Zeitzeile («Binance · pausiert bis 19:45», Bernstein wie «veraltet») — sonst stand das
 * nur im Bericht der letzten Aktualisierung, und die Zeile wirkte einfach alt.
 * Reine Logik (RowPauseTest, Parity `testdata/parity/row_pause.json`, Swift-Spiegel RowPause.swift).
 */
object RowPause {

    /** Ende der Pause je Börsen-Schlüssel aus den gespeicherten Zuständen; Zustände ohne Pausen-Ende fehlen. */
    fun pauses(states: Map<String, ExchangeBackoff.State>): Map<String, Long> =
        states.filterValues { it.pausedUntil > 0 }.mapValues { it.value.pausedUntil }

    /**
     * Ende der Pause, das die Zeile zeigt; null = normale Zeitzeile. Deaktiviertes Paar: nie (es wird ohnehin nicht
     * abgefragt). Abgelaufene oder unmögliche Pause (gleiche Grenze wie [ExchangeBackoff.isPaused]): nie.
     */
    fun shownUntil(disabled: Boolean, pausedUntil: Long?, now: Long): Long? {
        if (disabled || pausedUntil == null) return null
        return pausedUntil.takeIf { ExchangeBackoff.isPaused(ExchangeBackoff.State(pausedUntil = it), now) }
    }
}
