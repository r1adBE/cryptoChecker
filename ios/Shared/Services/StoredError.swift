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

    // MARK: Kurzer Börsentext für den Bericht

    /// Höchstlänge des Börsentexts im Bericht.
    static let maxDetail = 80

    /// Fehlertext der Börse, gekürzt für «Letzte Aktualisierung» (z. B. «Invalid symbol.»): ohne URLs, Hosts,
    /// IP-Adressen, Ausnahme-Klassen und Zeichenfolgen, die nach Schlüssel oder Kennung aussehen (mindestens 5 Ziffern,
    /// oder ab 12 Zeichen mit Ziffern), höchstens `maxDetail` Zeichen. nil, wenn der Text nichts über die allgemeine
    /// Ursache hinaus sagt (eigene Marker, HTTP-Code, Netz, Zeit, Rate-Limit) oder danach kaum etwas übrig bleibt.
    /// Wie `StoredError.kt` (Fälle in exchange_detail.json).
    static func exchangeDetail(_ raw: String?) -> String? {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || keep.contains(text) || localizedKeep.contains(text)
            || keepPrefixes.contains(where: { text.hasPrefix($0) }) { return nil }
        if RefreshReportLogic.httpStatus(text) != nil { return nil }
        switch RefreshReportLogic.classify(text) {
        case .TIMEOUT, .OFFLINE, .RATE_LIMIT, .UNAVAILABLE: return nil
        default: break
        }
        var t = replacing(#"^(?:[A-Za-z0-9_$]+\.)*[A-Z][A-Za-z0-9_$]*(?:Exception|Error)(?::\s*|$)"#, in: text, with: "")
        t = replacing(#"[A-Za-z][A-Za-z0-9+.-]*://\S+"#, in: t, with: " ")
        t = replacing(#"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b"#, in: t, with: " ")
        t = replacing(#"\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\b"#, in: t, with: " ")
        t = replacing(#"\b(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}\b"#, in: t, with: " ")
        t = t.split(whereSeparator: { $0.isWhitespace }).map(String.init).filter { !looksLikeKey($0) }
            .joined(separator: " ")
        // Was vom Entfernten übrig bleibt: leere Klammern und Anführungszeichen
        t = replacing(#"\(\s*\)|\[\s*\]|\{\s*\}|"\s*"|'\s*'|«\s*»"#, in: t, with: " ")
        t = t.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        t = t.trimmingCharacters(in: CharacterSet(charactersIn: " :;,-"))
        if t.filter({ $0.isLetter }).count < 3 { return nil }
        return t.count > maxDetail ? String(t.prefix(maxDetail - 1)).trimmingCharacters(in: .whitespaces) + "…" : t
    }

    /// Schlüssel, Kennung, Zeitstempel: mindestens 5 Ziffern, oder ab 12 Zeichen mit Ziffer.
    private static func looksLikeKey(_ token: String) -> Bool {
        let digits = token.filter { ("0"..."9").contains($0) }.count
        return digits >= 5 || (token.count >= 12 && digits > 0)
    }

    /// Börsentext für den Bericht einer Börse: der häufigste `exchangeDetail` unter den Fehlern der Ursache `reason`
    /// (bei Gleichstand der alphabetisch erste); nil ohne verwertbaren Text. Wie Android `reportDetail`.
    static func reportDetail(_ errors: [String?], reason: RefreshFailure?) -> String? {
        guard let reason else { return nil }
        var counts: [String: Int] = [:]
        for error in errors where RefreshReportLogic.classify(error) == reason {
            if let detail = exchangeDetail(error) { counts[detail, default: 0] += 1 }
        }
        return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.first?.key
    }

    private static func replacing(_ pattern: String, in text: String, with template: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        return regex.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
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
