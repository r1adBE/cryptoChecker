package com.cryptochecker.app.widget

/**
 * Ansicht des Widgets «Was gerade auffällt» (Einrichten-Seite; wie iOS `PulseWidgetViewOption`): Impuls (Schlagzeile,
 * Leitsatz, Coins), Gewinner und Verlierer 24 h oder Bitcoin-Halving (Termin nach Kalender). Ohne Wahl der Impuls.
 */
enum class PulseWidgetView {
    IMPULSE,
    MOVERS,
    HALVING,
    ;

    companion object {
        fun fromName(name: String?): PulseWidgetView = entries.firstOrNull { it.name == name } ?: IMPULSE
    }
}
