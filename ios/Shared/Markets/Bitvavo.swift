import Foundation

private let bitvavoAPI = "https://api.bitvavo.com/v2"

/// 24-h-Ticker: mit `market` ein Paar, ohne Parameter alle.
private let bitvavoDayTicker = bitvavoAPI + "/ticker/24h"

/// Bitvavo (EU, EUR-Paare), API v2. Kennung = `market` («BTC-EUR»).
final class Bitvavo: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Bitvavo",
            name: "Bitvavo",
            pairListURL: bitvavoAPI + "/markets",
            tickerURL: bitvavoDayTicker + "?market=%1$s",
            errorField: "error"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { bitvavoDayTicker }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("market"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for market in try JArray(string: body).allObjects() where market.optString("status") == "trading" {
            out.append(MarketPair(try market.string("base"), try market.string("quote"), try market.string("market")))
        }
        return out
    }

    // MARK: Lesen

    /// Ein 24-h-Eintrag. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("volumeQuote")
        // «open» bei /ticker/24h: Kurs am Anfang des gleitenden Fensters
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }
}
