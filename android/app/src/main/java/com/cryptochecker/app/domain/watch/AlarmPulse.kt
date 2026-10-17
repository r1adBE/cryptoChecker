package com.cryptochecker.app.domain.watch

/**
 * Glocke im Kopf der Merkliste pulsiert einmal, wenn bei offener App ein Alarm auslöst.
 * Grundlage ist die letzte Auslösung aller Alarme (`lastTriggeredAt`, Epoch-ms).
 * Reines Kotlin, getestet in AlarmPulseTest; Swift: `AlarmPulse` in WatchMotion.swift.
 */
object AlarmPulse {

    /** Dauer des Pulses (grösser und zurück), ms. */
    const val MILLIS = 240

    /** Grösste Vergrösserung der Glocke. */
    const val SCALE = 1.18f

    /**
     * Neue Auslösung seit dem zuletzt gesehenen Stand? [previous] null = erster Stand
     * (beim Öffnen schon vorhanden) → nein; zurückgesetzt (kleiner) → nein.
     */
    fun isNew(previous: Long?, current: Long?): Boolean =
        previous != null && current != null && current > 0L && current > previous
}
