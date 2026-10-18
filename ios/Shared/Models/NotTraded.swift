import Foundation

/// «Wird an der Börse nicht mehr gehandelt»: Die vollständige Kursliste der Börse führt das
/// Paar nicht mehr. Ein Zustand, kein Fehler — die Zeile bleibt mit ihrem Hinweis stehen,
/// aber alles, was auf Marktdaten beruht, schweigt: kein ⚡, kein «Warum?», keine
/// Aktivitäts-Mitteilungen, keine 24-h-Änderung, kein Mini-Chart, nicht im Puls, keine Alarme
/// auf dem alten Kurs. Wie `NotTraded.kt` (Android, mit NotTradedTest).
///
/// Nur Foundation: auch im Widget nutzbar.
enum NotTraded {
    /// Gespeicherter «Fehler» (`lastError`) für solche Paare — sprachunabhängig, die Anzeige übersetzt.
    static let marker = "Wird an der Börse nicht mehr gehandelt"

    /// Wird das Paar mit diesem `lastError` nicht mehr gehandelt?
    static func isMarker(_ lastError: String?) -> Bool { lastError == marker }

    /// Fehlt ein Paar in der vollständigen Kursliste, wird es erst einmal einzeln abgefragt — eine
    /// Liste kann ein Paar auch nur kurz auslassen. Ist es schon als nicht gehandelt gespeichert,
    /// keine weitere Einzelabfrage: Es bleibt dabei, bis die Liste es wieder führt (sonst kostete
    /// jeder Durchlauf je Paar eine Anfrage, die ohnehin scheitert — bei 131 Paaren rund 11 s).
    static func askSingleWhenMissing(lastError: String?) -> Bool { !isMarker(lastError) }

    /// Bestätigt die Einzelabfrage «nicht mehr gehandelt»? Nur bei einer klaren Aussage der Börse über
    /// das Paar: Antwort ohne Kurs (auch ganz ohne Fehlertext), ausdrücklich unbekanntes Instrument
    /// (z. B. «Instrument ID does not exist», «EQuery:Unknown asset pair», «Invalid symbol») oder
    /// HTTP 400/404. Jeder andere Fehler — unlesbare Antwort, unbekannte Ausnahme, Netz, Zeit,
    /// Rate-Limit, übrige 4xx/5xx, fehlender Adapter — ist ein gewöhnlicher Fehler: Das Paar bleibt mit
    /// seinem letzten Kurs stehen. Wie `NotTraded.kt`.
    static func confirmedBySingle(gotPrice: Bool, error: String?) -> Bool {
        if gotPrice { return false }
        guard let error, !error.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
        switch RefreshReportLogic.classify(error) {
        case .NO_DATA:
            return true
        case .OTHER:
            return isUnknownInstrument(error)
        case .SERVER:
            guard let code = RefreshReportLogic.httpStatus(error) else { return false }
            return confirmingHTTP.contains(code)
        default:
            return false
        }
    }

    /// Sagt der Fehlertext der Börse ausdrücklich, dass sie das Paar nicht kennt?
    static func isUnknownInstrument(_ error: String?) -> Bool {
        guard let lower = error?.lowercased() else { return false }
        return unknownInstrumentHints.contains { lower.contains($0) }
    }

    /// HTTP-Status, die über das Paar etwas sagen (Anfrage abgelehnt bzw. nicht gefunden).
    private static let confirmingHTTP: Set<Int> = [400, 404]

    /// Fehlertexte der Börsen für ein unbekanntes Paar (klein geschrieben); Android gleich.
    private static let unknownInstrumentHints = [
        "does not exist", "not exist", "unknown asset pair", "unknown pair", "invalid pair", "pair not found",
        "invalid symbol", "symbol invalid", "unknown symbol", "symbol not found", "not supported symbol",
        "invalid instrument", "unknown instrument", "instrument not found", "invalid currency pair",
        "invalid_currency_pair", "invalid market", "market not found", "delisted",
    ]

    /// 24-h-Veränderung zum Anzeigen (Änderung, Puls, Widgets): nil («—») bei nicht gehandelten Paaren.
    static func shownChange24h(lastError: String?, change24h: Double?) -> Double? {
        isMarker(lastError) ? nil : change24h
    }

    /// Ids der nicht gehandelten Paare.
    static func ids(_ watches: [Watch]) -> Set<Int64> {
        Set(watches.filter { isMarker($0.lastError) }.map(\.id))
    }

    /// `map` ohne die Einträge nicht gehandelter Paare (z. B. gespeicherte Aktivitäts-Signale).
    static func withoutIds<V>(_ map: [Int64: V], _ notTraded: Set<Int64>) -> [Int64: V] {
        guard !notTraded.isEmpty, map.keys.contains(where: { notTraded.contains($0) }) else { return map }
        return map.filter { !notTraded.contains($0.key) }
    }
}

extension Watch {
    /// Paar wird an seiner Börse nicht mehr gehandelt (`NotTraded`).
    var isNotTraded: Bool { NotTraded.isMarker(lastError) }

    /// 24-h-Veränderung zum Anzeigen: nil («—») bei nicht gehandelten Paaren.
    var shownChange24h: Double? { NotTraded.shownChange24h(lastError: lastError, change24h: change24h) }
}
