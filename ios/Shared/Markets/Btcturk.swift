import Foundation

/// BtcTurk (Türkei). API v2: https://docs.btcturk.com
final class Btcturk: SimpleMarket {
    init() {
        super.init(
            key: "Btcturk",
            name: "BtcTurk",
            pairsURL: "https://api.btcturk.com/api/v2/server/exchangeinfo",
            tickerURL: "https://api.btcturk.com/api/v2/ticker?pairSymbol=%1$s",
            ttsName: "B T C Turk",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.object("data").array("symbols").allObjects() {
            if item.optString("status") != "TRADING" { continue }
            try pairs.append(CurrencyPairInfo(item.string("numerator"), item.string("denominator"), item.string("name")))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)\(info.quote)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.array("data").object(0), &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.timestamp = json.optLong("timestamp")
        // „open“ = Kurs vor 24 h; dailyPercent (Prozent) nur als Ersatz.
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("open"))
            ?? Change24h.percent(json.optDouble("dailyPercent"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.btcturk.com/api/v2/ticker" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JObject(string: response).array("data").allObjects() {
            let pair = item.optString("pair")
            if pair.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[pair] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
