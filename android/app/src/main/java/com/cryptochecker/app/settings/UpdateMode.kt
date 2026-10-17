package com.cryptochecker.app.settings

/**
 * «Wie aktuell?» (Einstellungen › Kurs-Aktualisierung): eine Wahl statt der früheren drei
 * Schalter «Im Hintergrund aktualisieren», «Häufig aktualisieren» und «Live-Kurse». Wie iOS
 * `UpdateMode`. Reine Logik (ohne android.*), in JVM-Tests geprüft.
 *
 *  - [SPARSAM] (Standard, empfohlen, seit Build 43): nur die Hintergrund-Aktualisierung im gewählten Intervall (15 Min.),
 *    kein WebSocket. Bei offener App wird beim Öffnen aktualisiert.
 *  - [LIVE_OFFEN] (für Profis): Live-Kurse per WebSocket, solange die Merkliste offen
 *    ist, dazu die Hintergrund-Aktualisierung bei geschlossener App.
 *  - [IMMER_KURZ]: nur noch zum Lesen alter Daten und Sicherungen. Bis vor 1.0 ein
 *    Vordergrunddienst (dataSync) mit kurzem Intervall; Google Play erlaubt das für einen
 *    dauernden Kursticker nicht, darum entfernt. Wird beim Lesen immer zu [LIVE_OFFEN]
 *    ([withoutService]) und nie mehr gespeichert oder gesichert.
 *
 * Gespeichert und gesichert unter [name] (Schlüssel «update_mode» bzw. «updateMode»).
 * Übernahme aus den früheren Schaltern ([fromLegacy]): «Häufig aktualisieren» oder «Live-Kurse»
 * an → [LIVE_OFFEN], sonst [SPARSAM]. Die frühere Hintergrund-Aktualisierung läuft jetzt in jeder
 * Wahl (auch wer sie aus hatte, bekommt [SPARSAM]).
 */
enum class UpdateMode {
    SPARSAM,
    LIVE_OFFEN,

    /** Nur noch für alte Daten; siehe [withoutService]. */
    IMMER_KURZ;

    /** Live-Kurse per WebSocket, solange die Merkliste offen ist. */
    val liveWhileOpen: Boolean get() = this != SPARSAM

    /**
     * Frühere Schalter für Sicherungen, die ältere App-Versionen lesen (siehe [LegacyFlags]).
     * «Häufig aktualisieren» (liveService) ist immer aus — es gibt keinen Dauerdienst mehr.
     */
    val legacyFlags: LegacyFlags get() = LegacyFlags(
        backgroundUpdates = true,
        liveService = false,
        liveWebSocket = liveWhileOpen,
    )

    /** [IMMER_KURZ] (alte Daten) wird zu [LIVE_OFFEN]; alle anderen bleiben. */
    fun withoutService(): UpdateMode = if (this == IMMER_KURZ) LIVE_OFFEN else this

    /** Die früheren drei Schalter, abgeleitet aus der Wahl. */
    data class LegacyFlags(val backgroundUpdates: Boolean, val liveService: Boolean, val liveWebSocket: Boolean)

    companion object {
        val DEFAULT = SPARSAM

        /** Wählbare Werte (Einstellungen, Suche) — ohne [IMMER_KURZ]. */
        val selectable: List<UpdateMode> = listOf(SPARSAM, LIVE_OFFEN)

        /** Name wie gespeichert, ohne Umdeutung; unbekannt oder leer → null. */
        private fun storedOrNull(name: String?): UpdateMode? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }

        /**
         * Gespeicherter Name; «IMMER_KURZ» (alte Daten) → [LIVE_OFFEN]; unbekannt (neuere Version)
         * oder leer → null.
         */
        fun fromNameOrNull(name: String?): UpdateMode? = storedOrNull(name)?.withoutService()

        /**
         * Muss der gespeicherte Wert (neu) geschrieben werden? Ja, wenn er fehlt bzw. unbekannt ist
         * oder noch «IMMER_KURZ» lautet — der wird einmalig als [LIVE_OFFEN] gespeichert.
         */
        fun needsMigration(stored: String?): Boolean =
            storedOrNull(stored).let { it == null || it == IMMER_KURZ }

        /**
         * Aus den früheren Schaltern; fehlen alle drei, null (nichts zu übernehmen). Ein fehlender
         * einzelner Schalter zählt als «aus» (so war er ab Werk).
         */
        fun fromLegacy(
            liveService: Boolean?,
            liveWebSocket: Boolean?,
            backgroundUpdates: Boolean?,
        ): UpdateMode? = when {
            liveService == null && liveWebSocket == null && backgroundUpdates == null -> null
            liveService == true || liveWebSocket == true -> LIVE_OFFEN
            else -> SPARSAM
        }

        /**
         * Gespeicherte Einstellung beim ersten Lesen: der neue Wert, sonst die früheren Schalter,
         * sonst — bei einer bestehenden Installation ([existingInstall], Android speichert nur
         * geänderte Schlüssel) — [SPARSAM] wie das frühere Ab-Werk-Verhalten, sonst [DEFAULT].
         */
        fun resolve(
            stored: String?,
            legacyLiveService: Boolean?,
            legacyWebSocket: Boolean?,
            legacyBackground: Boolean?,
            existingInstall: Boolean,
        ): UpdateMode =
            fromNameOrNull(stored)
                ?: fromLegacy(legacyLiveService, legacyWebSocket, legacyBackground)
                ?: if (existingInstall) SPARSAM else DEFAULT

        /**
         * Aus einer Sicherung: «updateMode», sonst die früheren Schalter (ältere Sicherung); fehlt
         * alles, null = aktuelle Einstellung stehen lassen. Nie [IMMER_KURZ].
         */
        fun fromBackup(
            stored: String?,
            legacyLiveService: Boolean?,
            legacyWebSocket: Boolean?,
            legacyBackground: Boolean?,
        ): UpdateMode? =
            fromNameOrNull(stored)
                ?: fromLegacy(legacyLiveService, legacyWebSocket, legacyBackground)
    }
}
