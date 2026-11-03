import Foundation

private let foxbitAPI = "https://api.foxbit.com.br/rest/v3"

/// Foxbit (Brasilien), öffentliche API v3: https://docs.foxbit.com.br/rest/v3/ — Kennung wie «btcbrl» (klein).
/// Ticker unter `data` mit verschachtelten Teilen: `last_trade`, `rolling_24h` (gleitende 24 h), `best` (Buch).
struct Foxbit: SimpleExchange {
    let key: String = "Foxbit"
    let name: String = "Foxbit"
    let pairListURL: String = foxbitAPI + "/markets"
    let tickerURL: String = foxbitAPI + "/markets/%1$s/ticker/24hr"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).lowercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try json.array("data").optObject(0) else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    /// `{"error": {"message", "details": [...]}}`: die erste Einzelheit, sonst die Meldung.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let error = json.optObject("error") else { try noErrorText() }
        let detail = error.optArray("details")?.optString(0) ?? ""
        if !detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return detail }
        return try error.string("message")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Alle Märkte in einer Antwort (Aufzeichnung 03.11.2026: 131 von 131 der Paarliste).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { foxbitAPI + "/markets/ticker/24hr" }

    /// Ohne letzten Handel (oder mit Kurs 0) fällt der Markt still weg.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            let id = row.optString("market_symbol")
            guard !id.isEmpty, let trade = row.optObject("last_trade"),
                  !BulkTickerRows.inactivePrice(trade, "price") else { continue }
            bulkRow(id, tickers: &tickers, failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("data").allObjects() {
            out.append(ExchangePair(try market.object("base").string("symbol").uppercased(),
                                    try market.object("quote").string("symbol").uppercased(),
                                    try market.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs des letzten Handels, Gebot/Angebot aus `best`, Hoch/Tief/Volumen und `open` (Kurs vor 24 h) aus
    /// `rolling_24h`. Die Handelszeit kommt nur als ISO-Text und bleibt ungenutzt (Abrufzeit).
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.object("last_trade").double("price")
        let day = row.optObject("rolling_24h") ?? JObject([:])
        let best = row.optObject("best") ?? JObject([:])
        ticker.last = last
        ticker.bid = best.optObject("bid")?.optDoubleOrMissing("price") ?? Ticker.missing
        ticker.ask = best.optObject("ask")?.optDoubleOrMissing("price") ?? Ticker.missing
        ticker.high = day.optDoubleOrMissing("high")
        ticker.low = day.optDoubleOrMissing("low")
        ticker.vol = day.optDoubleOrMissing("volume")
        ticker.volQuote = day.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: day.optDouble("open"))
    }
}
