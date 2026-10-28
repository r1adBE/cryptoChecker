import Foundation

private let kucoinAPI = "https://api.kucoin.com/api"

/// KuCoin Spot: Paare aus v2/symbols, Kurse aus market/stats bzw. allTickers.
final class Kucoin: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Kucoin",
            name: "KuCoin",
            pairListURL: kucoinAPI + "/v2/symbols",
            tickerURL: kucoinAPI + "/v1/market/stats?symbol=%1$s"
        )
    }

    // MARK: Einzelkurs

    /// «time» ist die Serverzeit der Statistik, kein Handel — Zeit der Börse bleibt offen.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        Kucoin.copyRow(try json.object("data"), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { kucoinAPI + "/v1/market/allTickers" }

    /// Auch hier die Hüllenzeit «time» nicht übernehmen; ohne letzten Kurs (etwa umbenanntes «last») meldet
    /// `bulkRow` das Paar als Fehler.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).object("data").array("ticker").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { Kucoin.copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for symbol in try json.array("data").allObjects() {
            // Nur Paare, die gerade handelbar sind («enableTrading» muss ein Wahrheitswert sein)
            guard try symbol.bool("enableTrading") else { continue }
            out.append(ExchangePair(
                try symbol.string("baseCurrency"),
                try symbol.string("quoteCurrency"),
                try symbol.string("symbol")
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Einzel- und Sammelabfrage nutzen dieselben Feldnamen.
    private static func copyRow(_ row: JObject, into ticker: inout Ticker) {
        ticker.last = row.optDoubleOrMissing("last")
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.volQuote = row.optDoubleOrMissing("volValue")
        // changeRate: Bruchteil über gleitende 24 h
        ticker.change24hPercent = Change24h.fraction(row.optDouble("changeRate"))
    }
}
