import Foundation

private let bit2meAPI = "https://gateway.bit2me.com"

/// Bit2Me (Spanien), öffentliche API: https://api.bit2me.com/ — Kennung wie «BTC/EUR». Ticker als Liste (mit
/// `symbol` ein Eintrag, ohne alle Märkte); Fehler als `{"statusCode", "message"}`.
struct Bit2Me: SimpleExchange {
    let key: String = "Bit2Me"
    let name: String = "Bit2Me"
    let pairListURL: String = bit2meAPI + "/v1/trading/market-config"
    let tickerURL: String = bit2meAPI + "/v2/trading/tickers?symbol=%1$s"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "/" + info.quote.uppercased()
    }

    /// Liste mit dem einen Markt; leer = unbekannt.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try JArray(string: body).optObject(0) else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Ohne `symbol` jeder Markt (Aufzeichnung 03.11.2026: alle 274 freigegebenen der Paarliste).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bit2meAPI + "/v2/trading/tickers" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "close", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur `marketEnabled` «enabled» («frozen» = gesperrt); «BTC/EUR» → BTC, EUR.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try JArray(string: body).allObjects() where market.optString("marketEnabled") == "enabled" {
            let id = market.optString("symbol")
            let parts = id.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty }) else { continue }
            out.append(ExchangePair(parts[0].uppercased(), parts[1].uppercased(), id))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile (Zahlen als Zahl): `close` = letzter Kurs, `open` = Kurs vor 24 h (gleitend, er wandert mit der Zeit),
    /// Volumen in Basis- und Gegenwährung. `timestamp` ist nicht als Handelszeit dokumentiert — bleibt ungenutzt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("close")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }
}
