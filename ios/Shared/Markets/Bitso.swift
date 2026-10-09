import Foundation

/// Bitso (Lateinamerika). API v3: https://docs.bitso.com
final class Bitso: SimpleMarket {
    private static let base = "https://bitso.com/api/v3"

    init() {
        super.init(
            key: "Bitso",
            name: "Bitso",
            pairsURL: "\(Bitso.base)/available_books",
            tickerURL: "\(Bitso.base)/ticker?book=%1$s"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("payload").allObjects() {
            let book = item.optString("book")
            let parts = book.split(separator: "_").map(String.init)
            if parts.count != 2 { continue }
            pairs.append(CurrencyPairInfo(parts[0].uppercased(), parts[1].uppercased(), book))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.baseLower)_\(info.quoteLower)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("payload"), &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.timestamp = TimeUtils.isoToMillis(json.optString("created_at"))
        // change_24 = absolute Veränderung der letzten 24 h
        ticker.change24hPercent = Change24h.fromAbsolute(last: ticker.last, change: json.optDouble("change_24"))
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.object("error").string("message")
    }

    // Ohne „book“ liefert der Endpunkt alle Bücher.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "\(Bitso.base)/ticker" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JObject(string: response).array("payload").allObjects() {
            let book = item.optString("book")
            if book.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[book] = ticker
        }
        return tickers
    }
}
