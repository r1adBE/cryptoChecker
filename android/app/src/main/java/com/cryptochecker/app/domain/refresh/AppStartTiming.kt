package com.cryptochecker.app.domain.refresh

/**
 * App-Start bis zum ersten Bild der Merkliste (aus dem Zwischenspeicher), nur lokal
 * gemessen und im Bericht «Letzte Aktualisierung» unter «Ablauf» gezeigt — keine Telemetrie.
 * Reines Kotlin — wie `AppStartTiming.swift`.
 */
object AppStartTiming {

    /**
     * Lag zwischen Prozessstart und Aufbau der Oberfläche mehr als das, lief der Prozess
     * schon vorher (Widget, Hintergrund-Aktualisierung): dann ab dem Aufbau messen.
     */
    const val COLD_GAP_MILLIS = 5_000L

    /** Unplausibel lange Werte (Debugger, angehaltene App) werden verworfen. */
    const val MAX_MILLIS = 60_000L

    /**
     * Dauer bis zum ersten Bild (alle Zeiten auf derselben Uhr, z. B. uptime);
     * null = nicht messbar oder unplausibel.
     * @param processStart Prozessstart; null = unbekannt (dann ab [uiCreated]).
     * @param uiCreated Aufbau der Oberfläche (Activity bzw. App-Start).
     */
    fun elapsed(processStart: Long?, uiCreated: Long, firstFrame: Long): Long? {
        val from = if (processStart != null && processStart <= uiCreated && uiCreated - processStart <= COLD_GAP_MILLIS) {
            processStart
        } else {
            uiCreated
        }
        val millis = firstFrame - from
        return millis.takeIf { it in 0..MAX_MILLIS }
    }
}

/**
 * Kein Netz (#22/#23): keine Aktualisierung versuchen, die Liste zeigt ruhig «Offline · Stand 19:41».
 * Kommt das Netz zurück, genau EINE Aktualisierung.
 */
object OfflineGate {

    /** Nur der Wechsel offline → online löst die eine Aktualisierung aus (nicht der erste Wert). */
    fun resumeOnChange(previous: Boolean?, online: Boolean): Boolean = previous == false && online
}
