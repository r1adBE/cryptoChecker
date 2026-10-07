import Foundation

/// Indodax (Indonesien). Achtung: In der Paarliste ist „base_currency“ der
/// Gegenwert (IDR/USDT) und „traded_currency“ der gehandelte Coin.
/// https://github.com/btcid/indodax-official-api-docs
final class Indodax: SimpleMarket {
    init() {
        super.init(
            key: "Indodax",
            name: "Indodax",
            pairsURL: "https://indodax.com/api/pairs",
            tickerURL: "https://indodax.com/api/ticker/%1$s",
            errorPropertyName: "error_description"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            func flag(_ name: String) -> Bool {
                let v = item.optString(name).lowercased()
                return v == "1" || v == "true"
            }
            if flag("is_maintenance") || flag("is_market_suspended") { continue }
            try pairs.append(CurrencyPairInfo(item.string("traded_currency").uppercased(),
                                              item.string("base_currency").uppercased(),
                                              item.string("id")))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.baseLower)\(info.quoteLower)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("ticker"), base: info.baseLower, quote: info.quoteLower, &ticker)
    }

    private func read(_ json: JObject, base: String, quote: String, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("buy")
        ticker.ask = json.optDoubleNoData("sell")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("vol_\(base)")
        ticker.volQuote = json.optDoubleNoData("vol_\(quote)")
        ticker.timestamp = json.optLong("server_time")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://indodax.com/api/ticker_all" }

    /// Schlüssel „btc_idr“ → Paar-Kennung „btcidr“.
    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: response).object("tickers").forEachObject { key, item in
            let parts = key.split(separator: "_").map(String.init)
            guard parts.count == 2 else { return }
            var ticker = Ticker()
            do { try read(item, base: parts[0], quote: parts[1], &ticker) } catch { return }
            tickers[parts[0] + parts[1]] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
