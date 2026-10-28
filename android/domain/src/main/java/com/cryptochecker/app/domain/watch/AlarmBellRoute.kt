package com.cryptochecker.app.domain.watch

/**
 * Ziel der Glocke im Kopf der Merkliste: Ohne einen einzigen Alarm führt sie nicht
 * in eine leere Übersicht, sondern gleich dorthin, wo man einen anlegt. Reine Regel, Swift-Spiegel
 * `AlarmBellRoute.swift` (gleiche Testfälle in AlarmBellRouteTest / AlarmBellRouteTests).
 */
enum class AlarmBellRoute {
    /** Mindestens ein Alarm: Übersicht (auch das Menü «Alle Alarme» führt immer dorthin). */
    OVERVIEW,

    /** Kein Alarm, genau ein Paar: dessen Alarm-Seite, die ohne Alarm gleich den Editor öffnet. */
    EDITOR,

    /** Kein Alarm, mehrere Paare: erst das Paar wählen (gleiche Auswahl wie in der leeren Übersicht), dann der Editor. */
    PICK_PAIR,

    /** Kein Alarm und leere Merkliste: zuerst einen Coin hinzufügen. */
    ADD_COIN;

    companion object {
        /**
         * @param alarmCount alle Alarme, die die Übersicht zeigen würde (Paar-Alarme auch ausgeschaltet,
         *   Portfolio-Alarme nur mit eingeschaltetem Portfolio); null = noch nicht geladen → Übersicht,
         *   damit ein früher Tipp nie am falschen Ort landet
         * @param watchCount Paare der ganzen Merkliste (ohne Gruppen-Filter und Suche)
         */
        fun of(alarmCount: Int?, watchCount: Int): AlarmBellRoute = when {
            alarmCount == null || alarmCount > 0 -> OVERVIEW
            watchCount == 1 -> EDITOR
            watchCount > 1 -> PICK_PAIR
            else -> ADD_COIN
        }
    }
}
