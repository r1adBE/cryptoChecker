import Foundation

/// Poloniex Spot. API v3: https://api-docs.poloniex.com/spot/api/
final class Poloniex: SimpleMarket {
    init() {
        super.init(
            key: "Poloniex",
            name: "Poloniex",
            pairsURL: "https://api.poloniex.com/markets",
            tickerURL: "https://api.poloniex.com/markets/%1$s/ticker24h",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if item.optString("state") != "NORMAL" { continue }
            try pairs.append(CurrencyPairInfo(item.string("baseCurrencyName"), item.string("quoteCurrencyName"), item.string("symbol")))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)_\(info.quote)" }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        // Einzelabfrage liefert ein Objekt; zur Sicherheit auch ein Array annehmen.
        let trimmed = response.trimmingCharacters(in: .whitespacesAndNewlines)
        let json: JObject
        if trimmed.hasPrefix("[") {
            json = try JArray(string: trimmed).object(0)
        } else {
            json = try JObject(string: trimmed)
        }
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("close")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("quantity")
        ticker.volQuote = json.optDoubleNoData("amount")
        ticker.timestamp = json.optLong("ts")
        // ticker24h: open = Kurs vor 24 h; dailyChange (Bruchteil) nur als Ersatz.
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("open"))
            ?? Change24h.fraction(json.optDouble("dailyChange"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.poloniex.com/markets/ticker24h" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let symbol = item.optString("symbol")
            if symbol.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[symbol] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
