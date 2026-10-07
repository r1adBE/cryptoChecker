import Foundation

/// NonKYC.io. API v2 (https://api.nonkyc.io/api/v2). Die Marktliste nennt die
/// Paare „BASE/QUOTE“, Ticker erwarten „BASE_QUOTE“.
final class NonKyc: SimpleMarket {
    private static let base = "https://api.nonkyc.io/api/v2"

    init() {
        super.init(
            key: "NonKyc",
            name: "NonKYC",
            pairsURL: "\(NonKyc.base)/market/getlist",
            tickerURL: "\(NonKyc.base)/ticker/%1$s",
            ttsName: "Non K Y C"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if !(item.optBool("isActive") || item.optBool("active")) { continue }
            let parts = item.optString("symbol").split(separator: "/").map(String.init)
            if parts.count != 2 { continue }
            let primary = item.optString("primaryTicker")
            pairs.append(CurrencyPairInfo(primary.isEmpty ? parts[0] : primary, parts[1], "\(parts[0])_\(parts[1])"))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)_\(info.quote)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last_price")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("base_volume")
        ticker.volQuote = json.optDoubleNoData("target_volume")
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        if let error = json.optObject("error") { return try error.string("message") }
        let error = json.optString("error")
        if !error.isEmpty { return error }
        return try json.string("message")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "\(NonKyc.base)/tickers" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            var id = item.optString("ticker_id")
            if id.isEmpty {
                let base = item.optString("base_currency")
                let quote = item.optString("target_currency")
                if base.isEmpty || quote.isEmpty { continue }
                id = "\(base)_\(quote)"
            }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[id] = ticker
        }
        return tickers
    }
}
