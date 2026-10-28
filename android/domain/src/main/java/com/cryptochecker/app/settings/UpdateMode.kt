package com.cryptochecker.app.settings

/**
 * «Wie aktuell?» (Einstellungen › Kurs-Aktualisierung). Wie iOS `UpdateMode`. Reine Logik (ohne android.*),
 * in JVM-Tests geprüft.
 *
 *  - [SPARSAM] (Standard, empfohlen): nur die Hintergrund-Aktualisierung im gewählten Intervall (15 Min.),
 *    kein WebSocket. Bei offener App wird beim Öffnen aktualisiert.
 *  - [LIVE_OFFEN] (für Profis): Live-Kurse per WebSocket, solange die Merkliste offen
 *    ist, dazu die Hintergrund-Aktualisierung bei geschlossener App.
 *
 * Gespeichert und gesichert unter [name] (Schlüssel «update_mode» bzw. «updateMode»). Die
 * Hintergrund-Aktualisierung läuft in jeder Wahl.
 */
enum class UpdateMode {
    SPARSAM,
    LIVE_OFFEN;

    /** Live-Kurse per WebSocket, solange die Merkliste offen ist. */
    val liveWhileOpen: Boolean get() = this != SPARSAM

    companion object {
        val DEFAULT = SPARSAM

        /** Wählbare Werte (Einstellungen, Suche). */
        val selectable: List<UpdateMode> = listOf(SPARSAM, LIVE_OFFEN)

        /** Gespeicherter Name; unbekannt (neuere Version) oder leer → null. */
        fun fromNameOrNull(name: String?): UpdateMode? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
