import Foundation

private let nonKycAPI = "https://api.nonkyc.io/api/v2"

/// NonKYC.io (API v2). Marktliste mit «BASE/QUOTE», Ticker-Kennung «BASE_QUOTE».
final class NonKyc: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "NonKyc",
            name: "NonKYC",
            pairListURL: nonKycAPI + "/market/getlist",
            tickerURL: nonKycAPI + "/ticker/%1$s",
            ttsName: "Non K Y C"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? NonKyc.underscored(info.base, info.quote) }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    /// Fehler: {"error":{"message":…}}, {"error":"…"} oder {"message":…}.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if let nested = json.optObject("error") { return try nested.string("message") }
        let plain = json.optString("error")
        if plain.isEmpty { return try json.string("message") }
        return plain
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { nonKycAPI + "/tickers" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(NonKyc.rowId(row), row: row, priceField: "last_price", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try JArray(string: body).allObjects() {
            let active = market.optBool("isActive") || market.optBool("active")
            let sides = market.optString("symbol").split(separator: "/").map(String.init)
            guard active, sides.count == 2 else { continue }
            // «primaryTicker» ist das eigentliche Kürzel der Basis, sonst der linke Teil
            let primary = market.optString("primaryTicker")
            out.append(ExchangePair(primary.isEmpty ? sides[0] : primary, sides[1],
                                        NonKyc.underscored(sides[0], sides[1])))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("target_volume")
    }

    /// «ticker_id», sonst aus Basis und Ziel zusammengesetzt; ohne beides leer (Zeile fällt weg).
    private static func rowId(_ row: JObject) -> String {
        let tickerId = row.optString("ticker_id")
        if !tickerId.isEmpty { return tickerId }
        let base = row.optString("base_currency")
        let target = row.optString("target_currency")
        return base.isEmpty || target.isEmpty ? "" : underscored(base, target)
    }

    private static func underscored(_ base: String, _ quote: String) -> String { base + "_" + quote }
}
