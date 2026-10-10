package com.cryptochecker.app.data.portfolio

/**
 * Was [PortfolioLegacyImport] beim Öffnen der Portfolio-Datenbank tut — reine Entscheidung (Unit-Test).
 */
enum class PortfolioLegacyPlan {
    /** Kein Altbestand in der Hauptdatenbank: nichts zu tun. */
    NOTHING,

    /** Kopieren mit den alten Ids (Portfolio-Datenbank leer), danach Altbestand löschen. */
    COPY_KEEP_IDS,

    /**
     * Kopieren mit neuen Ids, danach Altbestand löschen: Die Portfolio-Datenbank hat schon Einträge, aber
     * keinen Merker (eine frühere Übernahme schlug fehl, der Nutzer hat inzwischen erfasst). Anhängen
     * statt überschreiben — nichts geht verloren.
     */
    COPY_NEW_IDS,

    /** Schon kopiert (Merker gesetzt), nur das Löschen des Altbestands fehlt noch (Abbruch dazwischen). */
    DROP_LEGACY;

    companion object {
        fun decide(legacyPresent: Boolean, alreadyImported: Boolean, portfolioEmpty: Boolean): PortfolioLegacyPlan = when {
            !legacyPresent -> NOTHING
            alreadyImported -> DROP_LEGACY
            portfolioEmpty -> COPY_KEEP_IDS
            else -> COPY_NEW_IDS
        }
    }
}
