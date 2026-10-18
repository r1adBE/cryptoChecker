package com.cryptochecker.app.domain.portfolio

/**
 * Älterer Stand im Wertverlauf: Ist die eigene Börse nicht erreichbar, nimmt der Verlauf ihre zuletzt gemerkten
 * Tageskerzen (besser als keine) — aber nie still als heutigen Verlauf. Die Quelle merkt je Coin, seit wann der Stand
 * gilt ([ownStale]: Coin → Zeit des Abrufs); die Karte zeigt dann «Stand <Zeit>» unter dem Chart.
 * Reine Logik, wie `PortfolioHistoryAsOf.swift`.
 */
object PortfolioHistoryAsOf {

    /**
     * Zeit für «Stand …»: der älteste gemerkte Stand unter den gezeichneten Coins [drawn]; null = alle frisch.
     * Coins ohne Verlauf (nicht gezeichnet) zählen nicht — über sie sagt die Karte schon anderes.
     */
    fun of(ownStale: Map<String, Long>, drawn: Collection<String>): Long? =
        drawn.mapNotNull { coin -> ownStale[coin]?.takeIf { it > 0 } }.minOrNull()

    /**
     * Abfrage gescheitert, es gilt ein gemerkter Stand von [savedAt]: Zeit, mit der die Reihe als älterer Stand markiert
     * wird — null, solange er jünger als [ttlMillis] ist (dann ist er der heutige Verlauf, nur evtl. kürzer).
     */
    fun staleSince(savedAt: Long, now: Long, ttlMillis: Long): Long? =
        savedAt.takeIf { !(it in 1..now && now - it < ttlMillis) }
}
