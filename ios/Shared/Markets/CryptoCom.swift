import Foundation

/// Crypto.com Exchange Spot. API v1; Ticker-Felder sind Einzelbuchstaben.
final class CryptoCom: SimpleMarket {
    init() {
        super.init(
            key: "CryptoCom",
            name: "Crypto.com",
            pairsURL: "https://api.crypto.com/exchange/v1/public/get-instruments",
            tickerURL: "https://api.crypto.com/exchange/v1/public/get-tickers?instrument_name=%1$s",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.object("result").array("data").allObjects() {
            if item.optString("inst_type") != "CCY_PAIR" || !item.optBool("tradable") { continue }
            try pairs.append(CurrencyPairInfo(item.string("base_ccy"), item.string("quote_ccy"), item.string("symbol")))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("result").array("data").object(0), &ticker)
    }

    /// a = letzter Kurs, b/k = Geld/Brief, h/l = Hoch/Tief, v = Menge, vv = Wert, t = Zeit
    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("a")
        ticker.bid = json.optDoubleNoData("b")
        ticker.ask = json.optDoubleNoData("k")
        ticker.high = json.optDoubleNoData("h")
        ticker.low = json.optDoubleNoData("l")
        ticker.vol = json.optDoubleNoData("v")
        ticker.volQuote = json.optDoubleNoData("vv")
        ticker.timestamp = json.optLong("t")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.crypto.com/exchange/v1/public/get-tickers" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JObject(string: response).object("result").array("data").allObjects() {
            let id = item.optString("i")
            if id.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[id] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
