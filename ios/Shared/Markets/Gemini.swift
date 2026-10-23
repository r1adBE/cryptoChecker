import Foundation

private let geminiAPI = "https://api.gemini.com/v1"

/// Bekannte Quote-Endungen, die längeren zuerst.
private let geminiQuoteEndings = ["rlusd", "gusd", "usdc", "usdt", "usd", "eur", "gbp", "sgd", "btc", "eth", "sol", "fil"]

/// Gemini Spot. `/v1/symbols` nennt nur zusammengeschriebene Symbole («btcusd», «aavegusd»); Basis und Quote trennt
/// eine Liste bekannter Quote-Endungen (`splitSymbols`), Perpetuals («…perp») fallen weg.
///
/// Passt mehr als eine Endung (z. B. «mogusd» = MOG/USD oder MO/GUSD), wird nicht geraten: solche Symbole holen
/// Basis/Quote aus `/v1/symbols/details/{symbol}` — als Folgeseiten der Paarliste, höchstens `maxDetails` je
/// Synchronisierung. Die Warteschlange dafür gehört der einzelnen Synchronisierung (`DetailPaging`), nicht der von allen
/// geteilten Börse. Die Antworten merkt sich die Börse (`knownDetails`); was über der Grenze liegt oder eine
/// Fehlerantwort bekam, fehlt vorerst in der Paarliste und kommt bei einer späteren Synchronisierung dran.
final class Gemini: SimpleMarket, PagedPairList, @unchecked Sendable { // @unchecked: `knownDetails` nur unter `knownLock` (NSLock)
    typealias Split = (symbol: String, base: String, quote: String)

    /// Schützt `knownDetails` (gleichzeitige Synchronisierungen lesen und ergänzen es aus verschiedenen Aufgaben).
    private let knownLock = NSLock()
    /// Geklärte mehrdeutige Symbole (Symbol → Paar; nil = kein Spot-Paar) — Wissen über die Börse, kein
    /// Blätterzustand, darum über Synchronisierungen hinweg geteilt.
    private var knownDetails: [String: MarketPair?] = [:]

    init() {
        super.init(
            key: "Gemini",
            name: "Gemini",
            pairListURL: geminiAPI + "/symbols",
            tickerURL: geminiAPI + "/pubticker/%1$s",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleOrMissing("bid")
        ticker.ask = json.optDoubleOrMissing("ask")
        // «volume»: {"BTC": "…", "USD": "…", "timestamp": …}; timestamp ist die Momentaufnahme, kein Handel
        guard let volume = json.optObject("volume") else { return }
        ticker.vol = volume.optDoubleOrMissing(info.base.uppercased())
        ticker.volQuote = volume.optDoubleOrMissing(info.quote.uppercased())
    }

    // MARK: Sammelabfrage: Preisliste aller Paare, nur der letzte Kurs

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { geminiAPI + "/pricefeed" }

    /// Keine 24-h-Veränderung: Die Einheit von `percentChange24h` ist unklar (Doku Prozent, Antworten eher Bruchteil)
    /// — dann lieber Kerzen. Fehlt der Preis oder ist er unlesbar (NaN), meldet `bulkRow` das Paar.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("pair").lowercased(), row: row, priceField: "price", tickers: &tickers,
                            failed: &failed) { $0.last = row.optDouble("price", .nan) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Je Synchronisierung eine eigene Warteschlange der Detail-Abfragen.
    func newPairListPaging() -> PairListPaging { DetailPaging(market: self) }

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        try Gemini.isDetailsPage(body) ? readDetailsPage(body) : readSymbolList(body)
    }

    // MARK: Lesen

    private static func isDetailsPage(_ body: String) -> Bool {
        body.first { !$0.isWhitespace } == "{"
    }

    /// Folgeseite mit den Details eines Symbols. Eine Fehlerantwort lässt das Symbol ungeklärt.
    private func readDetailsPage(_ body: String) throws -> [MarketPair] {
        let json = try JObject(string: body)
        guard Gemini.hasDetails(json) else { return [] }
        let pair = Gemini.detailPair(json)
        // unter dem Symbol der Antwort ablegen — Antworten können versetzt zur Abfrage kommen
        let symbol = Gemini.trimmed(json.optString("symbol")).lowercased()
        locked { knownDetails[symbol] = .some(pair) }
        return pair.map { [$0] } ?? []
    }

    private func readSymbolList(_ body: String) throws -> [MarketPair] {
        let symbols = try JArray(string: body).allStrings()
        let known = locked { knownDetails }
        let plan = Gemini.planSymbols(symbols, resolved: Set(known.keys))
        var out = plan.direct.map { MarketPair($0.base.uppercased(), $0.quote.uppercased(), $0.symbol) }
        // früher geklärte mehrdeutige Symbole: Basis/Quote aus deren Details
        for symbol in plan.cached {
            if case let .some(.some(pair)) = known[symbol] { out.append(pair) }
        }
        return out
    }

    /// Warteschlange einer Synchronisierung. Die erste Antwort ist die Liste: Sie legt die noch ungeklärten mehrdeutigen
    /// Symbole fest (`planSymbols`); jede weitere Antwort — Details oder Fehler — rückt eins weiter.
    private final class DetailPaging: PairListPaging {
        private let market: Gemini
        /// Noch anzufragende Symbole; nil, solange die Liste nicht gelesen ist.
        private var pending: [String]?

        init(market: Gemini) { self.market = market }

        func nextPage(call: Int, body: String) -> String? {
            var queue = pending ?? market.deferredSymbols(body)
            let symbol = queue.isEmpty ? nil : queue.removeFirst()
            pending = queue
            return symbol.map(Gemini.detailsURL)
        }
    }

    /// Eine unlesbare Liste ist beim Lesen schon gescheitert (`readPairList`) — dann keine Details.
    private func deferredSymbols(_ listBody: String) -> [String] {
        guard let symbols = try? JArray(string: listBody).allStrings() else { return [] }
        let resolved = Set(locked { knownDetails }.keys)
        return Gemini.planSymbols(symbols, resolved: resolved).deferred.map { $0.symbol }
    }

    private func locked<T>(_ work: () -> T) -> T {
        knownLock.lock()
        defer { knownLock.unlock() }
        return work()
    }

    private static func trimmed(_ text: String) -> String { text.trimmingCharacters(in: .whitespaces) }

    // MARK: Öffentliche Hilfen

    /// Höchstens so viele Detail-Abfragen je Synchronisierung (Seitengrenze minus die Liste selbst).
    static let maxDetails = Market.maxPairListPages - 1

    /// Symbole → (Symbol, Basis, Quote), alles klein; Perpetuals und Symbole ohne bekannte Endung fallen weg. Bei
    /// mehreren passenden Endungen («mogusd», «btcgusd») gewinnt die Trennung, deren Basis als eindeutiges Symbol in
    /// der Liste steht (btcusd → BTC); sonst (keine oder mehrere solche) die kürzere Quote. Nur eine vorläufige
    /// Trennung — mehrdeutige Symbole gehen trotzdem immer über die Details (`planSymbols`).
    static func splitSymbols(_ symbols: [String]) -> [(symbol: String, base: String, quote: String)] {
        classify(symbols).map { $0.split }
    }

    static func detailsURL(_ symbol: String) -> String { geminiAPI + "/symbols/details/" + symbol }

    /// Ergebnis der Planung: Paare direkt aus der Liste (`direct`), mehrdeutige für die Details (`deferred`), früher
    /// geklärte (`cached`, Symbole) und ungeklärte über der Grenze (`unresolved`, Symbole).
    struct Plan {
        let direct: [Split]
        let deferred: [Split]
        let cached: [String]
        let unresolved: [String]
    }

    /// Eindeutige Symbole direkt; mehrdeutige aus `resolved` oder über die Details — höchstens `maxDetails`, der Rest
    /// bleibt ungeklärt (nie geraten) bis zu einer späteren Synchronisierung.
    static func planSymbols(_ symbols: [String], resolved: Set<String> = []) -> Plan {
        var direct: [Split] = []
        var cached: [String] = []
        var waiting: [Split] = []
        for item in classify(symbols) {
            if !item.ambiguous {
                direct.append(item.split)
            } else if resolved.contains(item.split.symbol) {
                cached.append(item.split.symbol)
            } else {
                waiting.append(item.split)
            }
        }
        return Plan(direct: direct, deferred: Array(waiting.prefix(maxDetails)), cached: cached,
                    unresolved: waiting.dropFirst(maxDetails).map { $0.symbol })
    }

    /// Antwort von /v1/symbols/details mit Symbol, Basis und Quote (sonst eine Fehlerantwort).
    static func hasDetails(_ json: JObject) -> Bool {
        ["symbol", "base_currency", "quote_currency"].allSatisfy { !trimmed(json.optString($0)).isEmpty }
    }

    /// Paar aus /v1/symbols/details/{symbol} (`symbol`, `base_currency`, `quote_currency`). Perpetuals und
    /// Fehlerantworten → nil, es wird nichts geraten.
    static func detailPair(_ json: JObject) -> MarketPair? {
        guard hasDetails(json) else { return nil }
        let symbol = trimmed(json.optString("symbol")).lowercased()
        let swap = symbol.hasSuffix("perp") || json.optString("product_type") == "swap"
        if swap { return nil }
        let base = trimmed(json.optString("base_currency")).uppercased()
        let quote = trimmed(json.optString("quote_currency")).uppercased()
        return MarketPair(base, quote, symbol)
    }

    /// Vorläufige Trennung eines Symbols und ob mehr als eine Endung passt (→ Details).
    private static func classify(_ symbols: [String]) -> [(split: Split, ambiguous: Bool)] {
        var matches: [(symbol: String, endings: [String])] = []
        for raw in symbols {
            let symbol = raw.lowercased()
            if symbol.hasSuffix("perp") { continue }
            let endings = geminiQuoteEndings.filter { symbol.count > $0.count && symbol.hasSuffix($0) }
            if !endings.isEmpty { matches.append((symbol: symbol, endings: endings)) }
        }
        func stem(_ symbol: String, _ quote: String) -> String { String(symbol.dropLast(quote.count)) }
        let clearBases = Set(matches.filter { $0.endings.count == 1 }.map { stem($0.symbol, $0.endings[0]) })
        return matches.map { match -> (split: Split, ambiguous: Bool) in
            let backed = match.endings.filter { clearBases.contains(stem(match.symbol, $0)) }
            let quote = backed.count == 1 ? backed[0] : match.endings[match.endings.count - 1]
            let split: Split = (symbol: match.symbol, base: stem(match.symbol, quote), quote: quote)
            // auch mit genau einer bekannten Basis mehrdeutig: sie beweist die Trennung nicht
            return (split: split, ambiguous: match.endings.count > 1)
        }
    }
}
