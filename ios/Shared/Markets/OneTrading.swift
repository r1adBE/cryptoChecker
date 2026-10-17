import Foundation

private let oneTradingAPI = "https://api.onetrading.com/fast/v1"

/// One Trading (EU, vormals Bitpanda Pro), nur Spot. Doku: docs.onetrading.com
final class OneTrading: SimpleMarket {
    init() {
        super.init(
            key: "OneTrading",
            name: "One Trading",
            pairListURL: oneTradingAPI + "/instruments",
            tickerURL: oneTradingAPI + "/market-ticker/%1$s",
            errorField: "error"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "_" + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { oneTradingAPI + "/market-ticker" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("instrument_code"), row: row, priceField: "last_price", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for inst in try JArray(string: body).allObjects() {
            let activeSpot = inst.optString("type") == "SPOT" && inst.optString("state") == "ACTIVE"
            guard activeSpot else { continue }
            out.append(MarketPair(OneTrading.codeOf(inst, "base"), OneTrading.codeOf(inst, "quote"),
                                        try inst.string("id")))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        // Ohne Zeitangabe der Börse; «price_change_percentage» = Prozent über 24 h
        ticker.change24hPercent = Change24h.percent(row.optDouble("price_change_percentage"))
    }

    /// «code» des Unterobjekts `side` («base»/«quote»); fehlt es: leer (Paar fällt dann weg).
    private static func codeOf(_ inst: JObject, _ side: String) -> String {
        inst.optObject(side)?.optString("code") ?? ""
    }
}
