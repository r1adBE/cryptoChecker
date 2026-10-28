import Foundation

private let bitstampAPI = "https://www.bitstamp.net/api/v2"

/// Bitstamp (EU-reguliert, auch EUR-/GBP-Paare), API v2. Kennung = `url_symbol` («btcusd»).
final class Bitstamp: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Bitstamp",
            name: "Bitstamp",
            pairListURL: bitstampAPI + "/trading-pairs-info/",
            tickerURL: bitstampAPI + "/ticker/%1$s/",
            errorField: "reason"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage: alle Ticker; «pair» = «BTC/USD» → Kennung «btcusd»

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { bitstampAPI + "/ticker/" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            let pairId = row.optString("pair").replacingOccurrences(of: "/", with: "").lowercased()
            bulkRowIfActive(pairId, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try JArray(string: body).allObjects() where entry.optString("trading") == "Enabled" {
            // Anzeigename wie «BTC/USD»
            let label = try entry.string("name")
            guard let slash = label.firstIndex(of: "/") else { continue }
            let pairId = try entry.string("url_symbol")
            out.append(ExchangePair(String(label[..<slash]), String(label[label.index(after: slash)...]), pairId))
        }
        return out
    }

    // MARK: Lesen

    /// Ein Ticker-Eintrag. `timestamp` gehört zur Momentaufnahme, nicht zum letzten Handel — Zeit der Börse bleibt
    /// unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «open_24» = Kurs vor 24 h («open» wäre der Tagesbeginn); sonst die Prozentangabe
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open_24"))
            ?? Change24h.percent(row.optDouble("percent_change_24"))
    }
}
