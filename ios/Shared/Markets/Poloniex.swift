import Foundation

private let poloniexMarkets = "https://api.poloniex.com/markets"

/// Poloniex Spot (API v3, api-docs.poloniex.com/spot).
final class Poloniex: SimpleMarket {
    init() {
        super.init(
            key: "Poloniex",
            name: "Poloniex",
            pairListURL: poloniexMarkets,
            tickerURL: poloniexMarkets + "/%1$s/ticker24h",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "_" + info.quote }

    /// Normal ein Objekt; kommt doch eine Liste, zählt ihr erster Eintrag.
    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let row = try text.hasPrefix("[") ? JArray(string: text).object(0) : JObject(string: text)
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { poloniexMarkets + "/ticker24h" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "close", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for market in try JArray(string: body).allObjects() where market.optString("state") == "NORMAL" {
            out.append(MarketPair(
                try market.string("baseCurrencyName"),
                try market.string("quoteCurrencyName"),
                try market.string("symbol")
            ))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let close = try row.double("close")
        ticker.last = close
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("quantity")
        ticker.volQuote = row.optDoubleOrMissing("amount")
        // «ts» = Momentaufnahme, kein Handel. «open» = Kurs vor 24 h; «dailyChange» (Bruchteil) nur als Ersatz
        ticker.change24hPercent = Change24h.fromOpen(last: close, open: row.optDouble("open"))
            ?? Change24h.fraction(row.optDouble("dailyChange"))
    }
}
