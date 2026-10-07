import Foundation

final class Kucoin: SimpleMarket {
    private static let allTickersURL = "https://api.kucoin.com/api/v1/market/allTickers"

    init() {
        super.init(
            key: "Kucoin",
            name: "KuCoin",
            pairsURL: "https://api.kucoin.com/api/v2/symbols",
            tickerURL: "https://api.kucoin.com/api/v1/market/stats?symbol=%1$s"
        )
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let data = try json.object("data")
        readTicker(data, &ticker)
        ticker.timestamp = data.optLong("time")
    }

    /// Einzelabruf und Massenabfrage tragen dieselben Feldnamen.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) {
        ticker.bid = json.optDouble("buy", Ticker.noData)
        ticker.ask = json.optDouble("sell", Ticker.noData)

        ticker.vol = json.optDouble("vol", Ticker.noData)
        ticker.volQuote = json.optDouble("volValue", Ticker.noData)

        ticker.high = json.optDouble("high", Ticker.noData)
        ticker.low = json.optDouble("low", Ticker.noData)

        ticker.last = json.optDouble("last", Ticker.noData)
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { Kucoin.allTickersURL }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        let data = try JObject(string: response).object("data")
        let time = data.optLong("time")

        var tickers: [String: Ticker] = [:]
        for entry in try data.array("ticker").allObjects() {
            let symbol = entry.optString("symbol")
            if symbol.isEmpty { continue }

            var ticker = Ticker()
            readTicker(entry, &ticker)
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
        return tickers
    }

    // MARK: Paarliste

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for symbolData in try json.array("data").allObjects() {
            if try symbolData.bool("enableTrading") {
                try pairs.append(CurrencyPairInfo(
                    symbolData.string("baseCurrency"),
                    symbolData.string("quoteCurrency"),
                    symbolData.string("symbol")
                ))
            }
        }
        return pairs
    }
}
