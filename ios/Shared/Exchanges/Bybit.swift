import Foundation

private let bybitAPI = "https://api.bybit.com/v5/market"

/// Bybit Spot. API v5: https://bybit-exchange.github.io/docs/v5/market/tickers
struct Bybit: BybitBase {
    let key: String = "Bybit"
    let name: String = "Bybit"
    let apiCategory: String = "spot"
    let contractType: FuturesContractType = .none
}

/// Bybit USDT-/USDC-Perpetuals (Kategorie «linear»).
struct BybitFutures: BybitBase {
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    var category: ExchangeCategory { .futures }

    let key: String = "BybitFutures"
    let name: String = "Bybit Futures"
    let apiCategory: String = "linear"
    let contractType: FuturesContractType = .perpetual
}

/// Gemeinsamer Teil von Spot und Perpetuals; die Kategorie steht in jeder Adresse. Antworten haben die Form
/// {"retCode", "retMsg", "result": {"list": [...]}}. Die Paarliste fragt «limit=1000» an: linear hat über 500
/// Symbole, ohne Angabe kämen nur 500.
protocol BybitBase: SimpleExchange {
    /// Bybit-Kategorie der Adressen («spot», «linear»); nicht `category` (Abschnitt der Börsenauswahl, `Exchange`).
    var apiCategory: String { get }
    var contractType: FuturesContractType { get }
}

extension BybitBase {
    var pairListURL: String { "\(bybitAPI)/instruments-info?category=\(apiCategory)&limit=1000" }
    var tickerURL: String { "\(bybitAPI)/tickers?category=\(apiCategory)&symbol=%1$s" }
    var errorField: String? { "retMsg" }

    private var perpetuals: Bool { contractType == .perpetual }

    // MARK: Einzelkurs

    /// `time` der Antwort ist Serverzeit, kein Handel → Zeit der Börse unbekannt (wie Binance).
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try Self.resultList(json)
        if rows.isEmpty { throw JSONError(message: JSONError.noData) }
        try copyRow(rows.object(0), into: &ticker)
    }

    /// retCode 0 heisst Erfolg — «OK» ist dann kein Fehlertext; es bleibt beim Fehler des Parsers.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("retCode", -1) == 0 { try noErrorText() }
        return try simpleErrorMessage(json)
    }

    // MARK: Sammelabfrage (blättert nicht)

    var bulkTickersNumOfRequests: Int { 1 }

    /// Enthält alle gehandelten Symbole der Kategorie.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { "\(bybitAPI)/tickers?category=\(apiCategory)" }

    /// Die Serverzeit `time` wird nicht auf die Paare übertragen (siehe `fillTicker`).
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try Self.resultList(JObject(string: body)).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// instruments-info blättert mit `nextPageCursor` (höchstens 1000 je Seite); mit den TradFi-Perps kann linear
    /// darüber wachsen. Leerer Cursor = letzte Seite. Eine unlesbare Seite wirft — still aufhören hiesse, dass
    /// Paare der weiteren Seiten unbemerkt fehlen. Wie `Bybit.kt`.
    func pairListNextPage(call: Int, body: String) throws -> String? {
        guard let result = try JObject(string: body).optObject("result") else { return nil }
        let cursor = result.optString("nextPageCursor")
        if cursor.isEmpty { return nil }
        return (pairListAddress(call: call) ?? "") + "&cursor=" + bybitCursorParam(cursor)
    }

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try Self.resultList(json).allObjects() {
            guard entry.optString("status") == "Trading" else { continue }
            // Futures: nur Perpetuals (Laufzeit-Kontrakte tragen andere Symbole)
            if perpetuals && entry.optString("contractType") != "LinearPerpetual" { continue }
            // TradFi (Aktien, ETFs, Rohstoffe, Devisen) nur bei Perps; Spot-Token bleiben ohne Angabe
            let tradFi: Bool? = perpetuals ? TradFi.bybit(symbolType: entry.optString("symbolType")) : nil
            out.append(ExchangePair(
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
