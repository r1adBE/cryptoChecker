import Foundation

/// Älterer Stand im Wertverlauf: Ist die eigene Börse nicht erreichbar, nimmt der Verlauf ihre zuletzt gemerkten
/// Tageskerzen (besser als keine) — aber nie still als heutigen Verlauf. Die Quelle merkt je Coin, seit wann der Stand
/// gilt (`ownStale`: Coin → Zeit des Abrufs); die Karte zeigt dann «Stand <Zeit>» unter dem Chart.
/// Reine Logik, wie `PortfolioHistoryAsOf.kt`.
enum PortfolioHistoryAsOf {
    /// Zeit für «Stand …»: der älteste gemerkte Stand unter den gezeichneten Coins `drawn`; nil = alle frisch.
    /// Coins ohne Verlauf (nicht gezeichnet) zählen nicht — über sie sagt die Karte schon anderes.
    static func of(ownStale: [String: Int64], drawn: [String]) -> Int64? {
        drawn.compactMap { coin in ownStale[coin].flatMap { $0 > 0 ? $0 : nil } }.min()
    }

    /// Abfrage gescheitert, es gilt ein gemerkter Stand von `savedAt`: Zeit, mit der die Reihe als älterer Stand markiert
    /// wird — nil, solange er jünger als `ttl` ist (dann ist er der heutige Verlauf, nur evtl. kürzer).
    static func staleSince(savedAt: Int64, now: Int64, ttl: Int64) -> Int64? {
        // Uhr zurückgestellt (Stand «in der Zukunft»): wie frisch behandeln, nie eine künftige Zeit zeigen
        if savedAt > now { return nil }
        let fresh = savedAt >= 1 && now - savedAt < ttl
        return fresh ? nil : savedAt
    }
}
