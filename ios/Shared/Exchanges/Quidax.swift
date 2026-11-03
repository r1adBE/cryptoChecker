import Foundation

private let quidaxAPI = "https://app.quidax.io/api/v1/markets"

/// Quidax (Nigeria, SEC-Lizenz), öffentliche API: https://docs.quidax.io/ — Kennung wie «btcngn» (klein). Antworten
/// `{"status", "message", "data"}`; ein Ticker unter `data.ticker`, die Gesamtliste als Objekt je Markt.
struct Quidax: SimpleExchange {
    let key: String = "Quidax"
    let name: String = "Quidax"
    let pairListURL: String = quidaxAPI
    let tickerURL: String = quidaxAPI + "/tickers/%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).lowercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(try json.object("data").object("ticker"), into: &ticker)
    }

    /// `status` «error» mit `message`; «success» ist kein Fehler.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optString("status") != "error" { try noErrorText() }
        return try json.string("message")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Jeder Markt der Paarliste steht in der Gesamtliste (Aufzeichnung 03.11.2026: 79 von 79).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { quidaxAPI + "/tickers" }

    /// Kurs 0 (nie gehandelt) fällt still weg.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).object("data").forEachObject { id, entry in
            guard let row = entry.optObject("ticker") else { return }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("data").allObjects() {
            out.append(ExchangePair(try market.string("base_unit").uppercased(),
                                    try market.string("quote_unit").uppercased(), try market.string("id")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text): `buy`/`sell` = bestes Gebot/Angebot, `open` = Kurs vor 24 h, `vol` in der
    /// Basiswährung. Hoch/Tief «0.0» heisst: kein Handel in 24 h (keine Angabe). `at` ist die Zeit der Antwort.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = Quidax.positiveOrMissing(row.optDouble("buy"))
        ticker.ask = Quidax.positiveOrMissing(row.optDouble("sell"))
        ticker.high = Quidax.positiveOrMissing(row.optDouble("high"))
        ticker.low = Quidax.positiveOrMissing(row.optDouble("low"))
        let vol = row.optDouble("vol")
        ticker.vol = vol.isFinite && vol >= 0 ? vol : Ticker.missing
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }

    private static func positiveOrMissing(_ value: Double) -> Double { value.isFinite && value > 0 ? value : Ticker.missing }
}
