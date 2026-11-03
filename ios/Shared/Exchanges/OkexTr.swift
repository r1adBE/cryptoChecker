import Foundation

private let okxTrAPI = "https://tr.okx.com/api/v5"

/// OKX TR führt die Lira-Märkte; die übrigen Spot-Paare sind dieselben wie bei OKX und bleiben dort.
private let okxTrQuote = "TRY"

/// OKX TR (Türkei, CMB-Lizenzliste), API v5 wie OKX unter eigener Adresse: https://tr.okx.com/docs-v5/ — Kennung
/// «BTC-TRY». Paarliste aus `public/instruments` (Status «live», nur TRY), Kurse wie bei `Okex`.
struct OkexTr: SimpleExchange {
    let key: String = "OkexTr"
    let name: String = "OKX TR"
    let pairListURL: String = okxTrAPI + "/public/instruments?instType=SPOT"
    let tickerURL: String = okxTrAPI + "/market/ticker?instId=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "-" + info.quote.uppercased()
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try json.array("data").optObject(0) else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    /// `code` ≠ «0» mit `msg`; «0» ist kein Fehler.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        let code = json.optString("code")
        if code.isEmpty || code == "0" { try noErrorText() }
        return try json.string("msg")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Alle Spot-Ticker (auch die TRY-Märkte); jeder Markt mit Status «live» steht darin.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { okxTrAPI + "/market/tickers?instType=SPOT" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("instId"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Status «live» mit Gegenwährung TRY.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for inst in try json.array("data").allObjects()
        where inst.optString("state") == "live" && inst.optString("quoteCcy") == okxTrQuote {
            out.append(ExchangePair(try inst.string("baseCcy"), try inst.string("quoteCcy"), try inst.string("instId")))
        }
        return out
    }

    // MARK: Lesen

    /// Wie OKX: `open24h` = Kurs vor 24 h; `ts` ist die Erzeugung des Tickers, kein Handel.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("vol24h")
        ticker.volQuote = row.optDoubleOrMissing("volCcy24h")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open24h"))
    }
}
