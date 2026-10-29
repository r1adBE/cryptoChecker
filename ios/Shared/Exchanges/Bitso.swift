import Foundation

private let bitsoAPI = "https://bitso.com/api/v3"

/// Bitso (Lateinamerika), API v3: https://docs.bitso.com — Bücher heissen «btc_mxn».
struct Bitso: SimpleExchange {
    let key: String = "Bitso"
    let name: String = "Bitso"
    let pairListURL: String = bitsoAPI + "/available_books"
    let tickerURL: String = bitsoAPI + "/ticker?book=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.baseLower + "_" + info.quoteLower
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("payload"), into: &ticker)
    }

    /// Fehlerform {"error": {"message": …}}.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.object("error").string("message")
    }

    // MARK: Sammelabfrage: derselbe Endpunkt ohne «book» liefert alle Bücher

    var bulkTickersNumOfRequests: Int { 1 }

    func bulkTickersURL(call: Int) -> String? { bitsoAPI + "/ticker" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("payload").allObjects() {
            bulkRowIfActive(row.optString("book"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("payload").allObjects() {
            let book = entry.optString("book")
            let sides = book.split(separator: "_")
            if sides.count == 2 { out.append(ExchangePair(sides[0].uppercased(), sides[1].uppercased(), book)) }
        }
        return out
    }

    // MARK: Lesen

    /// Ein Buch-Eintrag. `created_at` ist die Zeit der Momentaufnahme, kein Handel — die Zeit der Börse bleibt
    /// unbekannt, sonst wirkte ein ruhiger Markt frisch.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «change_24»: absolute Veränderung über 24 h
        ticker.change24hPercent = Change24h.fromAbsolute(last: last, change: row.optDouble("change_24"))
    }
}
