import Foundation

/// One Trading (früher Bitpanda Pro), EU. Nur Spot. https://docs.onetrading.com
final class OneTrading: SimpleMarket {
    init() {
        super.init(
            key: "OneTrading",
            name: "One Trading",
            pairsURL: "https://api.onetrading.com/fast/v1/instruments",
            tickerURL: "https://api.onetrading.com/fast/v1/market-ticker/%1$s",
            errorPropertyName: "error"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if item.optString("type") != "SPOT" || item.optString("state") != "ACTIVE" { continue }
            let base = item.optObject("base")?.optString("code") ?? ""
            let quote = item.optObject("quote")?.optString("code") ?? ""
            try pairs.append(CurrencyPairInfo(base, quote, item.string("id")))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)_\(info.quote)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last_price")
        ticker.bid = json.optDoubleNoData("highest_bid")
        ticker.ask = json.optDoubleNoData("lowest_ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("base_volume")
        ticker.volQuote = json.optDoubleNoData("quote_volume")
        // Kein Zeitstempel in der Antwort → Abfragezeit
        // price_change_percentage = 24 h in Prozent
        ticker.change24hPercent = Change24h.percent(json.optDouble("price_change_percentage"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.onetrading.com/fast/v1/market-ticker" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let id = item.optString("instrument_code")
            if id.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[id] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
