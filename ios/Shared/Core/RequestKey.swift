import Foundation

/// Vergleichsschlüssel einer Adresse: dieselbe Adresse ohne den Abfrageparameter `timestamp=<Ziffern>`. BingX stempelt
/// jede Adresse schon beim Bilden mit der aktuellen Zeit (ms) — ohne diesen Schlüssel wären zwei sonst gleiche Anfragen
/// nie gleich: gleichzeitige gleiche Abrufe liefen doppelt (`InFlightRequests`), nach einer gescheiterten
/// Sammelabfrage folgte dieselbe Anfrage noch einmal als «ungefiltert» (`ExchangeService.loadBulkCall`), und das Widget
/// hielte die Sammelabfrage für gefiltert (`LowMemoryBulk.isUnfiltered`). Gesendet wird immer die echte Adresse mit
/// Stempel. Wie `RequestKey.kt`, Fälle in `request_key.json`.
enum RequestKey {
    static func of(_ url: String) -> String {
        guard let query = url.firstIndex(of: "?") else { return url }
        let params = url[url.index(after: query)...].split(separator: "&", omittingEmptySubsequences: false)
        let kept = params.filter { !isStamp($0) }
        if kept.count == params.count { return url }
        let head = String(url[..<query])
        return kept.isEmpty ? head : head + "?" + kept.joined(separator: "&")
    }

    /// `timestamp=` gefolgt von mindestens einer Ziffer und sonst nichts.
    private static func isStamp(_ param: Substring) -> Bool {
        guard param.hasPrefix(stamp) else { return false }
        let value = param.dropFirst(stamp.count)
        return !value.isEmpty && value.allSatisfy { ("0"..."9").contains($0) }
    }

    private static let stamp = "timestamp="
}
