import Foundation

/// MEXC Spot. Schnittstelle weitgehend wie Binance v3.
final class Mexc: SimpleMarket {
    init() {
        super.init(
            key: "Mexc",
            name: "MEXC",
            pairsURL: "https://api.mexc.com/api/v3/exchangeInfo",
            tickerURL: "https://api.mexc.com/api/v3/ticker/24hr?symbol=%1$s",
            errorPropertyName: "msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("symbols").allObjects() {
            // Status "1" = handelbar (ältere Antworten: "ENABLED").
            let status = item.optString("status")
            if status != "1" && status != "ENABLED" && status != "TRADING" { continue }
            if !item.optBool("isSpotTradingAllowed", true) { continue }
            try pairs.append(CurrencyPairInfo(item.string("baseAsset"), item.string("quoteAsset"), item.string("symbol")))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("lastPrice")
        ticker.bid = json.optDoubleNoData("bidPrice")
        ticker.ask = json.optDoubleNoData("askPrice")
        ticker.high = json.optDoubleNoData("highPrice")
        ticker.low = json.optDoubleNoData("lowPrice")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("closeTime")
        // openPrice = Kurs vor 24 h; priceChangePercent ist bei MEXC ein Bruchteil.
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("openPrice"))
            ?? Change24h.fraction(json.optDouble("priceChangePercent"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.mexc.com/api/v3/ticker/24hr" }

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

/// MEXC USDT-Perpetuals (contract.mexc.com).
final class MexcFutures: SimpleMarket {
    init() {
        super.init(
            key: "MexcFutures",
            name: "MEXC Futures",
            pairsURL: "https://contract.mexc.com/api/v1/contract/detail",
            tickerURL: "https://contract.mexc.com/api/v1/contract/ticker?symbol=%1$s",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            // state 0 = aktiv; futureType 1 = Perpetual
            if item.optInt("state", -1) != 0 { continue }
            if item.optInt("futureType", 1) != 1 { continue }
            let tradFi = TradFi.mexc(conceptPlates: TradFi.strings(item, "conceptPlate"), type: item.optInt("type", 1))
            try pairs.append(
                CurrencyPairInfo(item.string("baseCoin"), item.string("quoteCoin"), item.string("symbol"), .perpetual, tradFi: tradFi)
            )
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("data"), &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("lastPrice")
        ticker.bid = json.optDoubleNoData("bid1")
        ticker.ask = json.optDoubleNoData("ask1")
        ticker.high = json.optDoubleNoData("high24Price")
        ticker.low = json.optDoubleNoData("lower24Price")
        // volume24 zählt Kontrakte, nicht Coins — deshalb nur das Quote-Volumen.
        ticker.vol = Ticker.noData
        ticker.volQuote = json.optDoubleNoData("amount24")
        ticker.timestamp = json.optLong("timestamp")
        // riseFallRate = gleitende 24 h als Bruchteil (die Tageswerte stehen in riseFallRates)
        ticker.change24hPercent = Change24h.fraction(json.optDouble("riseFallRate"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://contract.mexc.com/api/v1/contract/ticker" }

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

    override var bulkTickersComplete: Bool { true }
}
