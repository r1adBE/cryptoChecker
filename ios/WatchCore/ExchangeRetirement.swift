import Foundation

/// Börsen, deren öffentliche Kurs-Schnittstelle zu einem festen Zeitpunkt endet (Entscheid Inhaber, 12.11.2026): Bitpanda
/// bis 31. Januar 2027, ab 1. Februar 2027 00:00 UTC von selbst — ohne App-Update — still gelegt. Ab dann keine Anfrage
/// mehr an diese Börse (Kurse, Sammelliste, Paarliste), ihre Paare bleiben in der Merkliste grau mit Grund (`marker`,
/// gespeichert als `lastError`, zählt als «nicht gehandelt», `NotTraded`), Alarme bleiben gespeichert, prüfen aber
/// nichts; im Explorer nicht mehr angeboten. Der CSV-Import der Exporte dieser Börse bleibt (Dateiformat, keine
/// Schnittstelle). Ein Zeitpunkt (UTC), keine Zeitzone; jede Prüfung nimmt `now` (Tests) oder `clock()`. Gemeinsame Regel für iPhone,
/// Widgets und Apple Watch (WatchCore, dazu im Widget-Ziel); wie Android `ExchangeRetirement.kt`
/// (Paritäts-Fixture `exchange_retirement.json`). Nur Foundation.
enum ExchangeRetirement {
    /// Schlüssel (= Name) der Börse Bitpanda.
    static let bitpanda = "Bitpanda"

    /// 1. Februar 2027 00:00:00 UTC (Epoch-ms): ab diesem Zeitpunkt liefert Bitpanda keine öffentlichen Kurse mehr.
    static let bitpandaEndMillis: Int64 = 1_801_440_000_000

    /// Anfang des gespeicherten «Fehlers» (`lastError`) eines stillgelegten Paars; danach der Name der Börse.
    static let markerPrefix = "Exchange retired: "

    /// Ende je Börse (Schlüssel bzw. Name, Gross-/Kleinschreibung egal).
    private static let ends: [String: Int64] = [bitpanda: bitpandaEndMillis]

    /// Uhr der Regel (Epoch-ms, Uhr des Geräts); Tests geben `now` ausdrücklich mit.
    static func clock() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    /// Ende der öffentlichen Kurse der Börse `exchange` (Schlüssel oder Name); nil = kein Ende bekannt.
    static func endsAt(_ exchange: String?) -> Int64? {
        guard let name = exchange?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        return ends.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// Ist die Börse `exchange` zum Zeitpunkt `now` stillgelegt (ab dem Ende einschliesslich)?
    static func isRetired(_ exchange: String?, now: Int64? = nil) -> Bool {
        guard let end = endsAt(exchange) else { return false }
        return (now ?? clock()) >= end
    }

    /// Gespeicherter «Fehler» für ein Paar der stillgelegten Börse `exchangeName`.
    static func marker(_ exchangeName: String) -> String {
        markerPrefix + exchangeName.trimmingCharacters(in: .whitespaces)
    }

    /// Ist `lastError` der Marker einer stillgelegten Börse?
    static func isMarker(_ lastError: String?) -> Bool { lastError?.hasPrefix(markerPrefix) == true }

    /// Name der Börse aus dem Marker; nil, wenn `lastError` keiner ist.
    static func exchange(of lastError: String?) -> String? {
        guard let lastError, isMarker(lastError) else { return nil }
        let name = String(lastError.dropFirst(markerPrefix.count)).trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? nil : name
    }

    /// Stillgelegte Börsen unter `exchanges` (in der Reihenfolge des ersten Vorkommens).
    static func retired(among exchanges: [String], now: Int64? = nil) -> [String] {
        let at = now ?? clock()
        var seen: Set<String> = []
        return exchanges.filter { isRetired($0, now: at) && seen.insert($0.lowercased()).inserted }
    }

    /// Hinweis in der Merkliste (mit ✕): die stillgelegten Börsen unter den Paaren (`exchanges`, Namen), ohne die schon
    /// mit ✕ geschlossenen (`dismissed`, klein geschrieben verglichen). Leer = kein Hinweis.
    static func noticeExchanges(_ exchanges: [String], dismissed: Set<String>, now: Int64? = nil) -> [String] {
        let closed = Set(dismissed.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        return retired(among: exchanges, now: now).filter { !closed.contains($0.trimmingCharacters(in: .whitespaces).lowercased()) }
    }
}
