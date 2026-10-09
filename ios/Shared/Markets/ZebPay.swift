import Foundation

/// ZebPay (Indien), Spot-API v2. https://github.com/zebpay/zebpay-api-references
final class ZebPay: SimpleMarket {
    private static let closed: Set<String> = ["closed", "close", "halt", "halted", "suspended", "delisted", "inactive", "disabled"]

    init() {
        super.init(
            key: "ZebPay",
            name: "ZebPay",
            pairsURL: "https://sapi.zebpay.com/api/v2/ex/exchangeInfo",
            tickerURL: "https://sapi.zebpay.com/api/v2/market/ticker?symbol=%1$s",
            ttsName: "Zeb Pay",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.object("data").array("symbols").allObjects() {
            if ZebPay.closed.contains(item.optString("status").lowercased()) { continue }
            if item.has("enableTrading") && !item.optBool("enableTrading", true) { continue }
            try pairs.append(CurrencyPairInfo(item.string("baseAsset"), item.string("quoteAsset"), item.string("symbol")))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)-\(info.quote)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("data"), &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("baseVolume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("timestamp")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://sapi.zebpay.com/api/v2/market/allTickers" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JObject(string: response).array("data").allObjects() {
            let symbol = item.optString("symbol")
            if symbol.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[symbol] = ticker
        }
        return tickers
    }
}
