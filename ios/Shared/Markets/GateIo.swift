import Foundation

final class GateIo: SimpleMarket {
    private static let allTickersURL = "https://api.gateio.ws/api/v4/spot/tickers"

    init() {
        super.init(
            key: "GateIo",
            name: "Gate.io",
            pairsURL: "https://api.gateio.ws/api/v4/spot/currency_pairs",
            tickerURL: "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=%1$s",
            ttsName: "Gate io",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for pair in try JArray(string: response).allObjects() {
            if try pair.string("trade_status") == "tradable" {
                try pairs.append(CurrencyPairInfo(
                    pair.string("base"),
                    pair.string("quote"),
                    pair.string("id")
                ))
            }
        }
        return pairs
    }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        let array = try JArray(string: response)
        if array.count < 1 { throw JSONError(message: "No data") }

        try readTicker(array.object(0), &ticker)
    }

    /// Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        // Ohne Orderbuch sendet Gate "" für highest_bid/lowest_ask: dann kein Geld-/Briefkurs statt Fehler
        ticker.bid = json.optDoubleNoData("highest_bid")
        ticker.ask = json.optDoubleNoData("lowest_ask")

        ticker.vol = try json.double("base_volume")
        ticker.volQuote = try json.double("quote_volume")

        ticker.high = try json.double("high_24h")
        ticker.low = try json.double("low_24h")
        ticker.last = try json.double("last")

        // change_percentage = gleitende 24 h in Prozent (change_utc0/utc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.percent(json.optDouble("change_percentage"))
    }

    /// Ohne currency_pair-Parameter liefert der Endpunkt alle Paare.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { GateIo.allTickersURL }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for entry in try JArray(string: response).allObjects() {
            let pairId = entry.optString("currency_pair")
            if pairId.isEmpty { continue }

            // Ein unlesbarer Eintrag lässt nur dieses Paar aus, nicht die ganze Abfrage
            var ticker = Ticker()
            do { try readTicker(entry, &ticker) } catch { continue }
            tickers[pairId] = ticker
        }
        return tickers
    }
}
