package com.cryptochecker.app.domain.watch

/**
 * Farbe der Statuszeile über der Merkliste (reine Logik, wie `StatusPillTone.swift`). Gleiche Reihenfolge wie ihr
 * Text: keine Verbindung (alle Paare scheitern am Netz) ist ein Fehler, «x veraltet» eine Warnung in Bernstein wie die
 * Zeile des veralteten Paars, gescheiterte Paare wieder ein Fehler. Kein gehandeltes Paar bzw. Gerät offline: neutral.
 */
enum class StatusPillTone {
    NEUTRAL, ERROR, WARNING, NORMAL;

    companion object {
        fun of(none: Boolean, deviceOffline: Boolean, offline: Boolean, stale: Int, failed: Int): StatusPillTone = when {
            none || deviceOffline -> NEUTRAL
            offline -> ERROR
            stale > 0 -> WARNING
            failed > 0 -> ERROR
            else -> NORMAL
        }
    }
}
