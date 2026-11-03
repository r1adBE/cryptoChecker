import Foundation

private let bitoproAPI = "https://api.bitopro.com/v3"

/// BitoPro (Taiwan), öffentliche API v3: https://github.com/bitoex/bitopro-offical-api-docs — Kennung wie «btc_twd»
/// (klein). Kurse unter `data` (Einzelkurs ein Objekt, Sammelabfrage eine Liste), ohne Gebot/Angebot.
struct BitoPro: SimpleExchange {
    let key: String = "BitoPro"
    let name: String = "BitoPro"
    let pairListURL: String = bitoproAPI + "/provisioning/trading-pairs"
    let tickerURL: String = bitoproAPI + "/tickers/%1$s"
    let errorField: String? = "error"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + "_" + info.quote).lowercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(try json.object("data"), into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Alle Paare in einer Antwort (Aufzeichnung 03.11.2026: 35 von 35 der Paarliste).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bitoproAPI + "/tickers" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("pair"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Paare in Wartung (`maintain` true) fallen weg.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("data").allObjects() where !market.optBool("maintain", false) {
            out.append(ExchangePair(try market.string("base").uppercased(), try market.string("quote").uppercased(),
                                    try market.string("pair")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text): Hoch/Tief und Volumen (Basiswährung) über 24 h, `priceChange24hr` =
    /// Veränderung über 24 h in Prozent. Keine Zeit des letzten Handels.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
        ticker.vol = row.optDoubleOrMissing("volume24hr")
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChange24hr"))
    }
}
