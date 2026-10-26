import Foundation

private let bybitAPI = "https://api.bybit.com/v5/market"

/// Bybit Spot. API v5: https://bybit-exchange.github.io/docs/v5/market/tickers
final class Bybit: BybitBase, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() { super.init(key: "Bybit", name: "Bybit", category: "spot", contractType: .none) }
}

/// Bybit USDT-/USDC-Perpetuals (Kategorie «linear»).
final class BybitFutures: BybitBase, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    /// Abschnitt «Futures» der Börsenauswahl (`MarketCategory`). Android gleich.
    override var category: MarketCategory { .futures }

    init() { super.init(key: "BybitFutures", name: "Bybit Futures", category: "linear", contractType: .perpetual) }
}

/// Gemeinsamer Teil von Spot und Perpetuals; die Kategorie steht in jeder Adresse. Antworten haben die Form
/// {"retCode", "retMsg", "result": {"list": [...]}}. Die Paarliste fragt «limit=1000» an: linear hat über 500
/// Symbole, ohne Angabe kämen nur 500.
class BybitBase: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    /// Bybit-Kategorie der Adressen («spot», «linear»); nicht `category` (Abschnitt der Börsenauswahl, `Market`).
    private let apiCategory: String
    private let contractType: FuturesContractType

    init(key: String, name: String, category: String, contractType: FuturesContractType) {
        self.apiCategory = category
        self.contractType = contractType
        super.init(
            key: key,
            name: name,
            pairListURL: "\(bybitAPI)/instruments-info?category=\(category)&limit=1000",
            tickerURL: "\(bybitAPI)/tickers?category=\(category)&symbol=%1$s",
            errorField: "retMsg"
        )
    }

    private var perpetuals: Bool { contractType == .perpetual }

    // MARK: Einzelkurs

    /// `time` der Antwort ist Serverzeit, kein Handel → Zeit der Börse unbekannt (wie Binance).
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try BybitBase.resultList(json)
        if rows.isEmpty { throw JSONError(message: JSONError.noData) }
        try copyRow(rows.object(0), into: &ticker)
    }

    /// retCode 0 heisst Erfolg — «OK» ist dann kein Fehlertext; es bleibt beim Fehler des Parsers.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("retCode", -1) == 0 { try noErrorText() }
        return try super.errorMessage(call: call, json: json, info: info)
    }

    // MARK: Sammelabfrage (blättert nicht)

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Enthält alle gehandelten Symbole der Kategorie.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { "\(bybitAPI)/tickers?category=\(apiCategory)" }

    /// Die Serverzeit `time` wird nicht auf die Paare übertragen (siehe `fillTicker`).
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try BybitBase.resultList(JObject(string: body)).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// instruments-info blättert mit `nextPageCursor` (höchstens 1000 je Seite); mit den TradFi-Perps kann linear
    /// darüber wachsen. Leerer Cursor = letzte Seite. Eine unlesbare Seite wirft — still aufhören hiesse, dass
    /// Paare der weiteren Seiten unbemerkt fehlen. Wie `Bybit.kt`.
    override func pairListNextPage(call: Int, body: String) throws -> String? {
        guard let result = try JObject(string: body).optObject("result") else { return nil }
        let cursor = result.optString("nextPageCursor")
        if cursor.isEmpty { return nil }
        return (pairListAddress(call: call) ?? "") + "&cursor=" + bybitCursorParam(cursor)
    }

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for entry in try BybitBase.resultList(json).allObjects() {
            guard entry.optString("status") == "Trading" else { continue }
            // Futures: nur Perpetuals (Laufzeit-Kontrakte tragen andere Symbole)
            if perpetuals && entry.optString("contractType") != "LinearPerpetual" { continue }
            // TradFi (Aktien, ETFs, Rohstoffe, Devisen) nur bei Perps; Spot-Token bleiben ohne Angabe
            let tradFi: Bool? = perpetuals ? TradFi.bybit(symbolType: entry.optString("symbolType")) : nil
            out.append(MarketPair(
                try entry.string("baseCoin"),
                try entry.string("quoteCoin"),
                try entry.string("symbol"),
                contractType,
                tradFi: tradFi
            ))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bid1Price")
        ticker.ask = row.optDoubleOrMissing("ask1Price")
        ticker.high = row.optDoubleOrMissing("highPrice24h")
        ticker.low = row.optDoubleOrMissing("lowPrice24h")
        ticker.vol = row.optDoubleOrMissing("volume24h")
        ticker.volQuote = row.optDoubleOrMissing("turnover24h")
        // gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(row.optDouble("price24hPcnt"))
    }

    /// Array `result.list` einer Bybit-Antwort.
    private static func resultList(_ envelope: JObject) throws -> JArray {
        try envelope.object("result").array("list")
    }
}

/// Bybit liefert `nextPageCursor` teils schon URL-kodiert («first%3DBTCUSDT%26last%3DETHUSDT») — dann so anhängen,
/// sonst kodieren. Wie `bybitCursorParam` (Android).
func bybitCursorParam(_ cursor: String) -> String {
    if cursor.contains("%") { return cursor }
    let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
    return cursor.addingPercentEncoding(withAllowedCharacters: unreserved) ?? cursor
}
