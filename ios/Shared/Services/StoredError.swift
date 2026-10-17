import Foundation

/// Fehlertext → kurzer technischer Code zum Speichern (`Watch.lastError`, beobachtete Adressen).
/// Rohe Fehlertexte und Server-Antworten können Hosts, URLs oder Adressen enthalten — gespeichert
/// wird nur, was Anzeige und Einordnung brauchen (`RefreshReportLogic.classify` bleibt gleich):
/// eigene Marker unverändert, sonst «HTTP 429 (retry-after 30 s)», «Too many requests», «Timeout»,
/// «NETWORK_OFFLINE» oder «ERROR:<Ausnahme>». Wie `StoredError.kt` (Fälle in stored_error.json).
enum StoredError {
    /// Eigene, sprachunabhängige Marker (schon ohne Details) — bleiben, wie sie sind.
    private static let keep: Set<String> = [
        NotTraded.marker,
        "Response data is empty", "Parsed ticker has no data", "Unknown error (empty)",
        "Market unavailable", "Börse nicht verfügbar", "Response too large",
        ConnectionErrors.offlineMarker, "timeout",
    ]

    /// Fehler der Massenabfrage («Bulk format error: 3 of 5 rows unreadable»): nur Zahlen, bleiben.
    private static let keepPrefixes = ["Bulk format error", "Bulk row unreadable"]

    private static let maxKept = 80

    static func sanitize(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return raw }
        if keep.contains(text) { return text }
        // Schon übersetzte, eigene Texte (iOS speichert sie so) bleiben
        if localizedKeep.contains(text) { return text }
        if keepPrefixes.contains(where: { text.hasPrefix($0) }) { return String(text.prefix(maxKept)) }
        if let code = RefreshReportLogic.httpStatus(text) {
            let retry = firstGroup(#"retry-after (\d+) s"#, in: text.lowercased())
            return "HTTP \(code)" + (retry.map { " (retry-after \($0) s)" } ?? "")
        }
        switch RefreshReportLogic.classify(text) {
        case .RATE_LIMIT: return "Too many requests"
        case .TIMEOUT: return "Timeout"
        case .OFFLINE: return ConnectionErrors.offlineMarker
        default:
            if let name = firstGroup(#"^(?:[\w$]+\.)*([A-Z][\w$]*(?:Exception|Error))\b"#, in: text) {
                return "ERROR:\(name)"
            }
            return "ERROR"
        }
    }

    private static var localizedKeep: Set<String> {
        [L("something_went_wrong"), L("market_data_empty_error"), L("market_unavailable_error")]
    }

    private static func firstGroup(_ pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: text)
        else { return nil }
        return String(text[range])
    }
}
