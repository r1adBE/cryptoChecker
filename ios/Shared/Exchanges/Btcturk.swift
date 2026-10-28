import Foundation

private let btcturkAPI = "https://api.btcturk.com/api/v2"

/// BtcTurk (Türkei), API v2: https://docs.btcturk.com — Kennung wie «BTCTRY».
final class Btcturk: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Btcturk",
            name: "BtcTurk",
            pairListURL: btcturkAPI + "/server/exchangeinfo",
            tickerURL: btcturkAPI + "/ticker?pairSymbol=%1$s",
            ttsName: "B T C Turk",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.array("data").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { btcturkAPI + "/ticker" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("pair"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for symbol in try json.object("data").array("symbols").allObjects() where symbol.optString("status") == "TRADING" {
            // «numerator» = Coin, «denominator» = Gegenwährung
            out.append(ExchangePair(try symbol.string("numerator"), try symbol.string("denominator"),
                                        try symbol.string("name")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile. `timestamp` gehört zur Momentaufnahme, nicht zum letzten Handel — Zeit der Börse bleibt
    /// unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «open» = Kurs vor 24 h; «dailyPercent» (Prozent) nur ersatzweise
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
            ?? Change24h.percent(row.optDouble("dailyPercent"))
    }
}
