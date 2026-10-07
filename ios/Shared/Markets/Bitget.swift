import Foundation

/// Bitget Spot. API v2.
final class Bitget: SimpleMarket {
    init() {
        super.init(
            key: "Bitget",
            name: "Bitget",
            pairsURL: "https://api.bitget.com/api/v2/spot/public/symbols",
            tickerURL: "https://api.bitget.com/api/v2/spot/market/tickers?symbol=%1$s",
            errorPropertyName: "msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            if item.optString("status") != "online" { continue }
            try pairs.append(CurrencyPairInfo(item.string("baseCoin"), item.string("quoteCoin"), item.string("symbol")))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try BitgetTicker.read(json.array("data").object(0), &ticker)
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.bitget.com/api/v2/spot/market/tickers" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        try BitgetTicker.readAll(response)
    }

    override var bulkTickersComplete: Bool { true }
}

/// Bitget USDT-Perpetuals.
final class BitgetFutures: SimpleMarket {
    init() {
        super.init(
            key: "BitgetFutures",
            name: "Bitget Futures",
            pairsURL: "https://api.bitget.com/api/v2/mix/market/contracts?productType=USDT-FUTURES",
            tickerURL: "https://api.bitget.com/api/v2/mix/market/ticker?symbol=%1$s&productType=USDT-FUTURES",
            errorPropertyName: "msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            if item.optString("symbolType") != "perpetual" || item.optString("symbolStatus") != "normal" { continue }
            try pairs.append(
                CurrencyPairInfo(item.string("baseCoin"), item.string("quoteCoin"), item.string("symbol"), .perpetual)
            )
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try BitgetTicker.read(json.array("data").object(0), &ticker)
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? {
        "https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES"
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        try BitgetTicker.readAll(response)
    }

    override var bulkTickersComplete: Bool { true }
}

/// Spot und Futures liefern dieselben Feldnamen.
enum BitgetTicker {
    static func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("lastPr")
        ticker.bid = json.optDoubleNoData("bidPr")
        ticker.ask = json.optDoubleNoData("askPr")
        ticker.high = json.optDoubleNoData("high24h")
        ticker.low = json.optDoubleNoData("low24h")
        ticker.vol = json.optDoubleNoData("baseVolume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("ts")
        // change24h = gleitende 24 h als Bruchteil; changeUtc24h (seit 0 Uhr UTC) bleibt weg.
        ticker.change24hPercent = Change24h.fraction(json.optDouble("change24h"))
    }

    static func readAll(_ response: String) throws -> [String: Ticker] {
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
