import Foundation

/// Bitstamp (EU-reguliert, auch EUR/GBP-Paare). API v2.
final class Bitstamp: SimpleMarket {
    init() {
        super.init(
            key: "Bitstamp",
            name: "Bitstamp",
            pairsURL: "https://www.bitstamp.net/api/v2/trading-pairs-info/",
            tickerURL: "https://www.bitstamp.net/api/v2/ticker/%1$s/",
            errorPropertyName: "reason"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if item.optString("trading") != "Enabled" { continue }
            let name = try item.string("name")                 // z. B. BTC/USD
            guard let slash = name.firstIndex(of: "/") else { continue }
            let base = String(name[..<slash])
            let quote = String(name[name.index(after: slash)...])
            try pairs.append(CurrencyPairInfo(base, quote, item.string("url_symbol")))
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
        ticker.timestamp = json.optLong("timestamp")
        // open_24 = Kurs vor 24 h („open“ wäre der Tagesbeginn und passt nicht).
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("open_24"))
            ?? Change24h.percent(json.optDouble("percent_change_24"))
    }

    // Alle Ticker auf einmal; Kennung „BTC/USD“ → url_symbol „btcusd“.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://www.bitstamp.net/api/v2/ticker/" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let pair = item.optString("pair")
            if pair.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[pair.replacingOccurrences(of: "/", with: "").lowercased()] = ticker
        }
        return tickers
    }
}
