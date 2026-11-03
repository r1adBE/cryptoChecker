import Foundation

private let valrAPI = "https://api.valr.com/v1/public"

/// VALR (Südafrika, FSCA-Lizenz), öffentliche API: https://docs.valr.com/ — Kennung wie «BTCZAR». Fehler als
/// `{"code", "message"}`.
struct Valr: SimpleExchange {
    let key: String = "Valr"
    let name: String = "VALR"
    let pairListURL: String = valrAPI + "/pairs"
    let tickerURL: String = valrAPI + "/%1$s/marketsummary"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).uppercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Nicht vollständig: Die Gesamtliste lässt aktive Spot-Paare aus (Aufzeichnung 03.11.2026: 33 von 204, z. B.
    /// DOGEUSDC) — ein fehlendes Paar fragt die App einzeln.
    func bulkTickersURL(call: Int) -> String? { valrAPI + "/marketsummary" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("currencyPair"), row: row, priceField: "lastTradedPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur aktive Spot-Paare (`currencyPairType` «SPOT»); Futures nicht.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for pair in try JArray(string: body).allObjects()
        where pair.optBool("active", false) && pair.optString("currencyPairType") == "SPOT" {
            out.append(ExchangePair(try pair.string("baseCurrency").uppercased(),
                                    try pair.string("quoteCurrency").uppercased(), try pair.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile (Zahlen als Text): `previousClosePrice` = Kurs vor 24 h (gleitend, wandert mit der Zeit;
    /// `changeFromPrevious` passt dazu), Hoch/Tief und Volumen über 24 h. `created` ist die Zeit der Momentaufnahme.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastTradedPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("previousClosePrice"))
    }
}
