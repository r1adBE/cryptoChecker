import Foundation

/// Upbit (Korea). Paare im Format QUOTE-BASE, z. B. KRW-BTC. https://docs.upbit.com
final class Upbit: UpbitStyleMarket {
    init() {
        super.init(key: "Upbit", name: "Upbit", ttsName: "Up bit", base: "https://api.upbit.com/v1",
                   pairsQuery: "is_details=false",
                   allTickersURL: "https://api.upbit.com/v1/ticker/all?quote_currencies=KRW,BTC,USDT")
    }
}

/// Bithumb (Korea), API v1 im selben Aufbau wie Upbit. https://apidocs.bithumb.com
final class Bithumb: UpbitStyleMarket {
    init() {
        super.init(key: "Bithumb", name: "Bithumb", ttsName: "Bithumb", base: "https://api.bithumb.com/v1",
                   pairsQuery: "isDetails=false", allTickersURL: nil)
    }
}

class UpbitStyleMarket: SimpleMarket {
    private let base: String
    private let allTickersURL: String?

    init(key: String, name: String, ttsName: String, base: String, pairsQuery: String, allTickersURL: String?) {
        self.base = base
        self.allTickersURL = allTickersURL
        super.init(key: key, name: name, pairsURL: "\(base)/market/all?\(pairsQuery)",
                   tickerURL: "\(base)/ticker?markets=%1$s", ttsName: ttsName)
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            let market = item.optString("market")
            let parts = market.split(separator: "-").map(String.init)
            if parts.count != 2 { continue }
            pairs.append(CurrencyPairInfo(parts[1], parts[0], market))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.quote)-\(info.base)" }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(JArray(string: response).object(0), &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("trade_price")
        ticker.high = json.optDoubleNoData("high_price")
        ticker.low = json.optDoubleNoData("low_price")
        ticker.vol = json.optDoubleNoData("acc_trade_volume_24h")
        ticker.volQuote = json.optDoubleNoData("acc_trade_price_24h")
        ticker.timestamp = json.optLong("timestamp")
        // Kein 24-h-Wert: signed_change_rate bezieht sich auf den Vortagesschluss (KST).
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        if let error = json.optObject("error") { return try error.string("message") }
        return try json.string("message")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { allTickersURL }

    /// Nur die beobachteten Paare; kennt die Börse eines nicht, folgt die ungefilterte Abfrage.
    override func bulkTickersURL(requestId: Int, pairIds: [String]) -> String? {
        pairIds.isEmpty ? allTickersURL : "\(base)/ticker?markets=\(pairIds.sorted().joined(separator: ","))"
    }

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
}
