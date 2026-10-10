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
                // Aktien (instCategory 3), Rohstoffe, Devisen: kein Krypto-Token
                pairs.append(CurrencyPairInfo(assets[0], assets[1], pairId, .perpetual,
                                              tradFi: TradFi.okx(instCategory: item.optString("instCategory"))))
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
        // Ohne Orders im Buch sendet OKX "" für bidPx/askPx: dann kein Geld-/Briefkurs statt Fehler
        ticker.bid = json.optDoubleNoData("bidPx")
        ticker.ask = json.optDoubleNoData("askPx")

        // Bei SWAP zählt vol24h Kontrakte, volCcy24h ist die Menge in der Basiswährung.
        // Ein Volumen in der Kotierungswährung liefert OKX für Swaps nicht – es wird nicht geschätzt.
        ticker.vol = try json.double("volCcy24h")
        ticker.volQuote = Ticker.noData

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
