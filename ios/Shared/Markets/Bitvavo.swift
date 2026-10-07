import Foundation

/// Bitvavo (EU, EUR-Paare). API v2.
final class Bitvavo: SimpleMarket {
    init() {
        super.init(
            key: "Bitvavo",
            name: "Bitvavo",
            pairsURL: "https://api.bitvavo.com/v2/markets",
            tickerURL: "https://api.bitvavo.com/v2/ticker/24h?market=%1$s",
            errorPropertyName: "error"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if item.optString("status") != "trading" { continue }
            try pairs.append(CurrencyPairInfo(item.string("base"), item.string("quote"), item.string("market")))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.volQuote = json.optDoubleNoData("volumeQuote")
        ticker.timestamp = json.optLong("timestamp")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.bitvavo.com/v2/ticker/24h" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let market = item.optString("market")
            if market.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[market] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
