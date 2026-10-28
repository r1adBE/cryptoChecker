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
    /// das Paar: Antwort ohne Kurs (auch Kurs 0), ausdrücklich unbekanntes Instrument
    /// (z. B. «Instrument ID does not exist», «EQuery:Unknown asset pair», «Invalid symbol») oder
    /// HTTP 400/404. Jeder andere Fehler — unlesbare Antwort, unbekannte Ausnahme, Netz, Zeit,
    /// Rate-Limit, übrige 4xx/5xx, fehlender Adapter, interner Fehler ohne Text — ist ein gewöhnlicher
    /// Fehler: Das Paar bleibt mit seinem letzten Kurs stehen. Wie `NotTraded.kt`.
    ///
    /// `failure`: beim Abruf festgestellte Art. Sagt sie etwas aus, entscheidet sie; bei Lesefehler und Meldung der
    /// Börse der Text. `.other` (interner Fehler ohne klaren Typ, z. B. ein Fehler ohne Text) bestätigt
    /// nie — sonst verstummte ein Paar wegen eines eigenen Fehlers für Stunden (BulkDelisted). Ein leerer Text
    /// bestätigt nur ohne Art (gespeicherte ältere Ergebnisse «ohne Kurs»); die Einzelabfrage selbst meldet «ohne
    /// Kurs» als Lesefehler («Parsed ticker has no data»).
    ///
    /// Meldet der Leser in einer sonst gültigen Antwort «kein Eintrag für das Paar» (`isMissingEntry`), bestätigt das
    /// ebenfalls — gleichwertig zu «Antwort ohne Kurs». Sicher, weil diese Prüfung nur nach einer vollständigen
    /// Kursliste ohne das Paar läuft (`PriceFetcher.fetchSingle(_:confirmMissing:)`): Zwei unabhängige Aussagen statt
    /// einer kurz leeren Antwort — oder ohne Sammelabfrage, wenn die Einzelabfrage selbst diese Liste liest
    /// (`confirmWithoutBulk`).
    static func confirmedBySingle(gotPrice: Bool, error: String?, failure: ExchangeFailure? = nil) -> Bool {
        if gotPrice { return false }
        switch failure {
        case .unknownInstrument?: return true
        case .httpStatus(let code)?: return confirmingHTTP.contains(code)
        case .timeout?, .offline?, .ssl?, .rateLimited?, .other?: return false
        case .parse?, .message?, nil: break
        }
        guard let error, !error.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return failure == nil }
        switch RefreshReportLogic.classify(error) {
        case .NO_DATA:
            return true
        case .OTHER:
            return isUnknownInstrument(error) || isMissingEntry(error)
        case .SERVER:
            guard let code = RefreshReportLogic.httpStatus(error) else { return false }
            return confirmingHTTP.contains(code)
        default:
            return false
        }
    }

    /// Einzelabfrage ohne Sammelabfrage (zu wenige Paare der Börse): wie nach dem Fehlen in der vollständigen Liste mit
    /// `confirmedBySingle` werten? Nur, wenn die Einzelabfrage selbst die vollständige Liste liest
    /// (`Exchange.singleTickerReadsFullList`, z. B. Lighter) — dann ist ihr «kein Eintrag» dieselbe Aussage wie das
    /// Fehlen in der Liste. Sonst fehlte die zweite, unabhängige Aussage: gewöhnlicher Fehler. Wie Android.
    static func confirmWithoutBulk(bulkTried: Bool, singleReadsFullList: Bool) -> Bool { !bulkTried && singleReadsFullList }

    /// Sagt der Fehlertext der Börse ausdrücklich, dass sie das Paar nicht kennt?
    static func isUnknownInstrument(_ error: String?) -> Bool {
        guard let lower = error?.lowercased() else { return false }
        return unknownInstrumentHints.contains { lower.contains($0) }
    }

    /// Meldung des eigenen Lesers: Antwort gültig, aber ohne Eintrag für das Paar — `JSONError.noData` («No data»,
    /// leere Liste bzw. Paar fehlt im Objekt) oder Hyperliquid «Unknown coin: …». Genau dieser Text, nicht jedes
    /// «no data» irgendwo in einer Meldung. Wie `NotTraded.kt`.
    static func isMissingEntry(_ message: String?) -> Bool {
        guard let text = message?.trimmingCharacters(in: .whitespacesAndNewlines) else { return false }
        return text == noEntry || text.lowercased().hasPrefix(unknownCoin)
    }

    /// = `JSONError.noData` (hier nur Foundation, auch im Widget); Android `ExchangeParseException.NO_DATA`.
    private static let noEntry = "No data"

    /// Anfang der Hyperliquid-Meldung für einen Coin, den die Antwort nicht führt (klein geschrieben).
    private static let unknownCoin = "unknown coin"

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
