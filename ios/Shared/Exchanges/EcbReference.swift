import Foundation

private let frankfurterAPI = "https://api.frankfurter.dev/v1"

/// Devisen-Referenzkurse der Europäischen Zentralbank über Frankfurter (frei, ohne Schlüssel): einmal je
/// Bankarbeitstag festgelegt, nie live — darum `priceIsDailyReference` («Referenzkurs, täglich») und keine Alarme
/// (`allowsAlarms`). Paare = alle geordneten Paare der Währungen aus `/currencies`, Kennung «EURUSD» (zwei
/// ISO-Codes). Kurs und Veränderung aus der Zeitreihe der letzten `lookbackDays` Tage: jüngster Wert gegen den
/// vorigen EZB-Tag. Sammelabfrage je Basiswährung. Wie `EcbReference.kt`.
final class EcbReference: Exchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    /// Schlüssel der Quelle (Sicherung, Merkliste).
    static let key = "EcbReference"
    /// So weit reicht die Zeitreihe zurück: genug für zwei EZB-Tage auch über Feiertage und Wochenenden.
    static let lookbackDays = 10

    init() {
        super.init(key: EcbReference.key, name: "ECB (Frankfurter)", ttsName: "E C B")
    }

    override var category: ExchangeCategory { .forex }
    override var allowsAlarms: Bool { false }
    override var priceIsDailyReference: Bool { true }

    // MARK: Einzelkurs

    /// Kennung nicht genau sechs Grossbuchstaben: «keine Daten» ohne Anfrage (Android wirft in `tickerAddress`).
    override func tickerQueryError(call: Int, info: PairQuery) -> Error? {
        EcbReference.split(info.pairId) == nil ? JSONError(message: JSONError.noData) : nil
    }

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        guard let pair = EcbReference.split(info.pairId) else { return "" }
        return EcbReference.seriesURL(base: pair.base, symbols: [pair.quote], nowMillis: EpochTime.nowMillis)
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let pair = EcbReference.split(info.pairId) else { throw JSONError(message: JSONError.noData) }
        try EcbReference.read(json, quote: pair.quote, into: &ticker)
    }

    /// Fehlertext von Frankfurter: {"message": "not found"}; ohne «message» keiner. Wie Android.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        let message = json.optString("message")
        if message.isEmpty { try noErrorText() }
        return message
    }

    // MARK: Sammelabfrage: je Basiswährung eine Zeitreihe

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        let groups = EcbReference.bulkGroups(pairIds)
        guard groups.indices.contains(call) else { return nil }
        return EcbReference.seriesURL(base: groups[call].base, symbols: groups[call].symbols,
                                      nowMillis: EpochTime.nowMillis)
    }

    override func bulkTickersRequestCount(pairIds: [String]) -> Int { EcbReference.bulkGroups(pairIds).count }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let json = try JObject(string: body)
        let base = try json.string("base")
        var symbols = Set<String>()
        for (_, value) in try json.object("rates").raw {
            if let day = value as? [String: Any] { symbols.formUnion(day.keys) }
        }
        var tickers: [String: Ticker] = [:]
        for symbol in symbols.sorted() {
            bulkRow(base + symbol, tickers: &tickers, failed: &failed) {
                try EcbReference.read(json, quote: symbol, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    override func pairListAddress(call: Int) -> String? { frankfurterAPI + "/currencies" }

    /// {"AUD": "Australian Dollar", …} → alle geordneten Paare (Basis ≠ Gegenwährung) der gültigen Codes.
    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        let codes = json.keys.filter(EcbReference.isCode).sorted()
        if codes.isEmpty { throw JSONError(message: JSONError.noData) }
        var out: [ExchangePair] = []
        for base in codes {
            for quote in codes where quote != base { out.append(ExchangePair(base, quote, base + quote)) }
        }
        return out
    }

    // MARK: Lesen und Hilfen

    /// `rates` {Datum: {Code: Wert}}: jüngster Wert = Kurs, Veränderung gegen den Wert des vorigen EZB-Tags.
    /// Keine Zeit der Börse (der Tag steht im Label, eine Börsenzeit liesse jede Zeile «veraltet» wirken).
    static func read(_ json: JObject, quote: String, into ticker: inout Ticker) throws {
        let rates = try json.object("rates")
        var series: [(day: String, value: Double)] = []
        for day in rates.keys.sorted() {
            guard let row = rates.optObject(day), row.has(quote) else { continue }
            let value = row.optDouble(quote)
            if value.isFinite, value > 0 { series.append((day, value)) }
        }
        guard let latest = series.last else { throw JSONError(message: JSONError.noData) }
        ticker.last = latest.value
        ticker.change24hPercent = series.count >= 2
            ? Change24h.fromOpen(last: latest.value, open: series[series.count - 2].value) : nil
    }

    /// «EURUSD» → ("EUR", "USD"); nil ohne genau zwei Codes aus je drei Grossbuchstaben.
    static func split(_ pairId: String?) -> (base: String, quote: String)? {
        guard let id = pairId, id.count == 6 else { return nil }
        let base = String(id.prefix(3))
        let quote = String(id.suffix(3))
        guard isCode(base), isCode(quote) else { return nil }
        return (base, quote)
    }

    /// Genau drei Grossbuchstaben A–Z (ISO-4217-Form).
    static func isCode(_ text: String) -> Bool {
        text.unicodeScalars.count == 3 && text.unicodeScalars.allSatisfy { $0.value >= 65 && $0.value <= 90 }
    }

    /// Gruppen der Sammelabfrage: je Basis (sortiert) die Gegenwährungen ohne Doppelte, sortiert. Ungültige
    /// Kennungen fehlen (Einzelabfrage meldet sie). Wie Android.
    static func bulkGroups(_ pairIds: [String]) -> [(base: String, symbols: [String])] {
        var byBase: [String: Set<String>] = [:]
        for raw in pairIds {
            guard let pair = split(raw.trimmingCharacters(in: .whitespacesAndNewlines)),
                  pair.base != pair.quote else { continue }
            byBase[pair.base, default: []].insert(pair.quote)
        }
        return byBase.keys.sorted().map { (base: $0, symbols: byBase[$0, default: []].sorted()) }
    }

    /// `/v1/{start}..?base=B&symbols=A,C` mit start = UTC-Datum `lookbackDays` Tage vor `nowMillis`.
    static func seriesURL(base: String, symbols: [String], nowMillis: Int64) -> String {
        frankfurterAPI + "/" + startDate(nowMillis: nowMillis) + "..?base=" + base + "&symbols="
            + symbols.joined(separator: ",")
    }

    /// yyyy-MM-dd (UTC) `lookbackDays` Tage vor `nowMillis`.
    static func startDate(nowMillis: Int64) -> String {
        let seconds = Double(nowMillis) / 1000 - Double(lookbackDays) * 86_400
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.formatOptions = [.withFullDate]
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}
