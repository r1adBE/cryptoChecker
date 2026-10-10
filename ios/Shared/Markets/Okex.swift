import Foundation

final class Okex: SimpleMarket {
    init() {
        super.init(
            key: "Okex",
            name: "OKX",
            pairsURL: "https://www.okx.com/api/v5/market/tickers?instType=SPOT",
            tickerURL: "https://www.okx.com/api/v5/market/ticker?instId=%1$s",
            errorPropertyName: "msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            let pairId = try item.string("instId")
            // components(separatedBy:) behält leere Teile — wie Kotlins split().
            let assets = pairId.components(separatedBy: "-")

            if assets.count == 2 {
                pairs.append(CurrencyPairInfo(assets[0], assets[1], pairId))
            }
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? {
        info.pairId ?? "\(info.base)-\(info.quote)"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try readTicker(json.array("data").object(0), &ticker)
    }

    /// Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        // Ohne Orders im Buch sendet OKX "" für bidPx/askPx: dann kein Geld-/Briefkurs statt Fehler
        ticker.bid = json.optDoubleNoData("bidPx")
        ticker.ask = json.optDoubleNoData("askPx")

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

            // Ein unlesbarer Eintrag lässt nur dieses Paar aus, nicht die ganze Abfrage
            var ticker = Ticker()
            do { try readTicker(entry, &ticker) } catch { continue }
            tickers[instId] = ticker
        }
        return tickers
    }
}
