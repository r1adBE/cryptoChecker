import Foundation

private let budaAPI = "https://www.buda.com/api/v2"

/// Buda (Chile, Kolumbien, Peru), öffentliche API v2: https://api.buda.com/ — Kennung wie «BTC-CLP». Beträge kommen
/// als Paar [Wert, Währung]; `price_variation_24h` ist ein Anteil (−0.037 = −3,7 %).
struct Buda: SimpleExchange {
    let key: String = "Buda"
    let name: String = "Buda"
    let pairListURL: String = budaAPI + "/markets"
    let tickerURL: String = budaAPI + "/markets/%1$s/ticker"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "-" + info.quote.uppercased()
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let row = try json.object("ticker")
        try copyRow(row, into: &ticker)
        ticker.bid = Buda.amount(row, "max_bid")
        ticker.ask = Buda.amount(row, "min_ask")
        ticker.vol = Buda.amount(row, "volume")
        ticker.volQuote = Buda.amount(row, "quote_volume")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Jeder Markt in einer Antwort (Aufzeichnung 03.11.2026: 26 von 26), nur Kurs und Veränderung.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { budaAPI + "/tickers" }

    /// Kurs 0 fällt still weg.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("tickers").allObjects() {
            let id = row.optString("market_id")
            if id.isEmpty { continue }
            if let price = row.optArray("last_price"), BulkTickerRows.inactivePrice(price, 0) { continue }
            bulkRow(id, tickers: &tickers, failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Gesperrte Märkte (`disabled`) fallen weg.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("markets").allObjects() where !market.optBool("disabled", false) {
            out.append(ExchangePair(try market.string("base_currency").uppercased(),
                                    try market.string("quote_currency").uppercased(), try market.string("id")))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs und Veränderung über 24 h (Anteil × 100); Einzel- und Sammelabfrage. Keine Zeit des letzten Handels.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.array("last_price").double(0)
        ticker.change24hPercent = Change24h.fraction(row.optDouble("price_variation_24h"))
    }

    /// Wert eines Betrags [Wert, Währung]; fehlt er, «keine Angabe».
    private static func amount(_ row: JObject, _ field: String) -> Double {
        guard let value = row.optArray(field)?.optDouble(0), value.isFinite else { return Ticker.missing }
        return value
    }
}
