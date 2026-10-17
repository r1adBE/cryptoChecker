import Foundation

/// Alle Spot-Ticker — zugleich Paarliste und Sammelabfrage.
private let okxSpotTickers = "https://www.okx.com/api/v5/market/tickers?instType=SPOT"

/// OKX Spot. Kennung «BASE-QUOTE».
final class Okex: SimpleMarket {
    init() {
        super.init(
            key: "Okex",
            name: "OKX",
            pairListURL: okxSpotTickers,
            tickerURL: "https://www.okx.com/api/v5/market/ticker?instId=%1$s",
            errorField: "msg"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.array("data").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { okxSpotTickers }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("instId"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for row in try json.array("data").allObjects() {
            let instId = try row.string("instId")
            // leere Teile zählen mit (wie Kotlin `split`): «A--B» ist kein Paar
            let legs = instId.components(separatedBy: "-")
            if legs.count == 2 { out.append(MarketPair(legs[0], legs[1], instId)) }
        }
        return out
    }

    // MARK: Lesen

    /// Je Paar dieselbe Struktur in Einzel- und Sammelabfrage.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.high = try row.double("high24h")
        ticker.low = try row.double("low24h")
        ticker.vol = try row.double("vol24h")
        ticker.volQuote = try row.double("volCcy24h")
        // Leeres Buch: OKX schickt "" für bidPx/askPx — dann ohne Geld-/Briefkurs statt Fehler
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        // «ts» = Erzeugung des Tickers (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
        // «open24h» = Kurs vor 24 h (sodUtc0/sodUtc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open24h"))
    }
}
