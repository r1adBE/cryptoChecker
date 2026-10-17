import Foundation

private let zebPayAPI = "https://sapi.zebpay.com/api/v2"

/// Statuswerte (klein geschrieben), bei denen ein Paar nicht handelbar ist.
private let zebPayStopped: Set<String> = ["closed", "close", "halt", "halted", "suspended", "delisted", "inactive",
                                          "disabled"]

/// ZebPay (Indien), Spot-API v2. Referenz: github.com/zebpay/zebpay-api-references
final class ZebPay: SimpleMarket {
    init() {
        super.init(
            key: "ZebPay",
            name: "ZebPay",
            pairListURL: zebPayAPI + "/ex/exchangeInfo",
            tickerURL: zebPayAPI + "/market/ticker?symbol=%1$s",
            ttsName: "Zeb Pay",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("data"), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { zebPayAPI + "/market/allTickers" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for symbol in try json.object("data").array("symbols").allObjects() where ZebPay.isTradable(symbol) {
            out.append(MarketPair(try symbol.string("baseAsset"), try symbol.string("quoteAsset"),
                                        try symbol.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// «timestamp» = Momentaufnahme (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
    }

    /// Gesperrter Status oder ausdrücklich «enableTrading»: false → nicht handelbar.
    private static func isTradable(_ symbol: JObject) -> Bool {
        if zebPayStopped.contains(symbol.optString("status").lowercased()) { return false }
        return !symbol.has("enableTrading") || symbol.optBool("enableTrading", true)
    }
}
