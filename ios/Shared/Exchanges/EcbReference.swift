import Foundation

/// Devisen-Referenzkurse der Europäischen Zentralbank, direkt von der EZB (`EcbRates`, frei, ohne Schlüssel): einmal
/// je Bankarbeitstag festgelegt, nie live — darum `priceIsDailyReference` («Referenzkurs, täglich») und keine Alarme
/// (`allowsAlarms`). Paare = alle geordneten Paare der Währungen des jüngsten Tags in `eurofxref-daily.xml` samt EUR,
/// Kennung «EURUSD» (zwei ISO-Codes). Kurs und Veränderung aus `eurofxref-hist-90d.xml`: jüngster Wert innerhalb von
/// `windowDays` Tagen vor dem jüngsten EZB-Tag gegen den vorigen EZB-Tag; andere Basis als EUR als Kreuzkurs
/// (`EcbRates.cross`). Eine Sammelabfrage für alle Paare. Wie `EcbReference.kt`.
struct EcbReference: Exchange {
    /// Schlüssel der Quelle (Sicherung, Merkliste).
    static let key = "EcbReference"
    /// Zeitfenster vor dem jüngsten EZB-Tag: genug für den vorigen EZB-Tag auch über Feiertage und Wochenenden.
    static let windowDays = 10

    let key: String = EcbReference.key
    let name: String = "ECB"
    let ttsName: String = "E C B"

    var category: ExchangeCategory { .forex }
    var allowsAlarms: Bool { false }
    var priceIsDailyReference: Bool { true }

    // MARK: Einzelkurs

    /// Kennung nicht genau sechs Grossbuchstaben: «keine Daten» ohne Anfrage (Android wirft in `tickerAddress`).
    func tickerQueryError(call: Int, info: PairQuery) -> Error? {
        EcbReference.split(info.pairId) == nil ? JSONError(message: JSONError.noData) : nil
    }

    func tickerAddress(call: Int, info: PairQuery) -> String {
        EcbReference.split(info.pairId) == nil ? "" : EcbRates.hist90dURL
    }

    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        guard let pair = EcbReference.split(info.pairId) else { throw JSONError(message: JSONError.noData) }
        let window = EcbRates.parseXML(body).map { EcbRates.recent($0, windowDays: EcbReference.windowDays) } ?? []
        try EcbReference.read(window, base: pair.base, quote: pair.quote, into: &ticker)
    }

    /// Die Dateien der EZB kennen keinen Fehlertext. Wie Android.
    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        try noErrorText()
    }

    // MARK: Sammelabfrage: eine Datei für alle Paare

    var bulkTickersNumOfRequests: Int { 1 }

    func bulkTickersURL(call: Int) -> String? { call == 0 ? EcbRates.hist90dURL : nil }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        guard let days = EcbRates.parseXML(body) else { throw JSONError(message: JSONError.noData) }
        let window = EcbRates.recent(days, windowDays: EcbReference.windowDays)
        let codes = Set(window.flatMap { EcbRates.codes($0) }).sorted()
        var tickers: [String: Ticker] = [:]
        for base in codes {
            for quote in codes where quote != base {
                bulkRow(base + quote, tickers: &tickers, failed: &failed) {
                    try EcbReference.read(window, base: base, quote: quote, into: &$0)
                }
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func pairListAddress(call: Int) -> String? { EcbRates.dailyURL }

    /// Jüngster Tag der EZB: alle geordneten Paare (Basis ≠ Gegenwährung), EUR eingeschlossen.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        guard let days = EcbRates.parseXML(body), let newest = days.keys.max(), let day = days[newest] else {
            throw JSONError(message: JSONError.noData)
        }
        let codes = EcbRates.codes(day)
        var out: [ExchangePair] = []
        for base in codes {
            for quote in codes where quote != base { out.append(ExchangePair(base, quote, base + quote)) }
        }
        return out
    }

    // MARK: Lesen und Hilfen

    /// Tage `window` (aufsteigend): jüngster Wert = Kurs, Veränderung gegen den Wert des vorigen EZB-Tags.
    /// Keine Zeit der Börse (der Tag steht im Label, eine Börsenzeit liesse jede Zeile «veraltet» wirken). Wie Android
    /// `copySeries`.
    static func read(_ window: [[String: Double]], base: String, quote: String, into ticker: inout Ticker) throws {
        let values = window.compactMap { EcbRates.cross($0, base: base, quote: quote) }
        guard let latest = values.last else { throw JSONError(message: JSONError.noData) }
        ticker.last = latest
        ticker.change24hPercent = values.count >= 2
            ? Change24h.fromOpen(last: latest, open: values[values.count - 2]) : nil
    }

    /// «EURUSD» → ("EUR", "USD"); nil ohne genau zwei Codes aus je drei Grossbuchstaben.
    static func split(_ pairId: String?) -> (base: String, quote: String)? {
        guard let id = pairId, id.count == 6 else { return nil }
        let base = String(id.prefix(3))
        let quote = String(id.suffix(3))
        guard EcbRates.isCode(base), EcbRates.isCode(quote) else { return nil }
        return (base, quote)
    }
}
