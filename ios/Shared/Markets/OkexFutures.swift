import Foundation

final class OkexFutures: SimpleMarket {
    init() {
        super.init(
            key: "OkexFutures",
            name: "OKX Futures",
            pairsURL: "https://www.okx.com/api/v5/market/tickers?instType=SWAP",
            tickerURL: "https://www.okx.com/api/v5/market/ticker?instId=%1$s"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            let pairId = try item.string("instId")
            let assets = pairId.components(separatedBy: "-")

            if assets.count == 3 && assets[2] == "SWAP" {
                pairs.append(CurrencyPairInfo(assets[0], assets[1], pairId, .perpetual))
            }
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        // OKX verpackt Fehler in code/msg statt in "data".
        let msg = json.optString("msg")
        if !msg.isEmpty { throw JSONError(message: msg) }

        try readTicker(json.array("data").object(0), &ticker)
    }

    /// Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.bid = try json.double("bidPx")
        ticker.ask = try json.double("askPx")

        ticker.vol = try json.double("vol24h")
        ticker.volQuote = try json.double("volCcy24h")

        ticker.high = try json.double("high24h")
        ticker.low = try json.double("low24h")

        ticker.last = try json.double("last")
        ticker.timestamp = try json.long("ts")

        // open24h = Kurs vor 24 h (sodUtc0/sodUtc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("open24h"))
    }

    /// Derselbe Endpunkt wie für die Paarliste liefert alle Ticker mit.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { currencyPairsURL(requestId: 0) ?? "" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for entry in try JObject(string: response).array("data").allObjects() {
            let instId = entry.optString("instId")
            if instId.isEmpty { continue }

            var ticker = Ticker()
            try readTicker(entry, &ticker)
            tickers[instId] = ticker
        }
        return tickers
    }
}
