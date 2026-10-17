import Foundation

/// Gemeinsame API-Wurzel von Spot und Futures.
private let bitgetAPI = "https://api.bitget.com/api/v2"

/// Produktart der USDT-Perpetuals, Teil jeder Futures-Adresse.
private let bitgetUsdtFutures = "productType=USDT-FUTURES"

/// Bitget Spot (API v2).
final class Bitget: BitgetBase {
    init() {
        super.init(
            key: "Bitget",
            name: "Bitget",
            pairListURL: bitgetAPI + "/spot/public/symbols",
            tickerURL: bitgetAPI + "/spot/market/tickers?symbol=%1$s",
            allTickersURL: bitgetAPI + "/spot/market/tickers"
        )
    }

    override func pairOf(_ entry: JObject) throws -> MarketPair? {
        guard entry.optString("status") == "online" else { return nil }
        return MarketPair(try entry.string("baseCoin"), try entry.string("quoteCoin"), try entry.string("symbol"))
    }
}

/// Bitget USDT-Perpetuals.
final class BitgetFutures: BitgetBase {
    init() {
        super.init(
            key: "BitgetFutures",
            name: "Bitget Futures",
            pairListURL: bitgetAPI + "/mix/market/contracts?" + bitgetUsdtFutures,
            tickerURL: bitgetAPI + "/mix/market/ticker?symbol=%1$s&" + bitgetUsdtFutures,
            allTickersURL: bitgetAPI + "/mix/market/tickers?" + bitgetUsdtFutures
        )
    }

    override func pairOf(_ entry: JObject) throws -> MarketPair? {
        let perpetual = entry.optString("symbolType") == "perpetual"
        let normal = entry.optString("symbolStatus") == "normal"
        guard perpetual, normal else { return nil }
        return MarketPair(
            try entry.string("baseCoin"),
            try entry.string("quoteCoin"),
            try entry.string("symbol"),
            .perpetual,
            tradFi: TradFi.bitget(isRwa: entry.optString("isRwa"))
        )
    }
}

/// Spot und Futures unterscheiden sich nur in Adressen und Paarfilter (`pairOf`); Antworten haben die Form
/// {"code", "msg", "data": [...]}, die Ticker-Felder heissen gleich.
class BitgetBase: SimpleMarket {
    private let allTickersURL: String

    init(key: String, name: String, pairListURL: String, tickerURL: String, allTickersURL: String) {
        self.allTickersURL = allTickersURL
        super.init(key: key, name: name, pairListURL: pairListURL, tickerURL: tickerURL, errorField: "msg")
    }

    /// Paar aus einem Eintrag der Paarliste; nil = nicht wählbar. Jede Unterklasse muss das liefern (in Kotlin abstrakt).
    func pairOf(_ entry: JObject) throws -> MarketPair? {
        fatalError("\(key): pairOf(_:) fehlt")
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.array("data").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { allTickersURL }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPr", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        try json.array("data").allObjects().compactMap { entry in try pairOf(entry) }
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPr")
        ticker.bid = row.optDoubleOrMissing("bidPr")
        ticker.ask = row.optDoubleOrMissing("askPr")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // `ts` = Zeitpunkt der Momentaufnahme, kein Handel → Zeit der Börse unbekannt.
        // «change24h» ist gleitend (Bruchteil); «changeUtc24h» (ab 0 Uhr UTC) passt nicht.
        ticker.change24hPercent = Change24h.fraction(row.optDouble("change24h"))
    }
}
