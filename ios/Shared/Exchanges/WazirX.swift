import Foundation

private let wazirxAPI = "https://api.wazirx.com/sapi/v1"

/// WazirX (Indien), öffentliche API: https://docs.wazirx.com/ — Kennung wie «btcinr» (klein). Fehler als
/// `{"code", "message"}`.
struct WazirX: SimpleExchange {
    let key: String = "WazirX"
    let name: String = "WazirX"
    let pairListURL: String = wazirxAPI + "/exchangeInfo"
    let tickerURL: String = wazirxAPI + "/ticker/24hr?symbol=%1$s"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).lowercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Nicht vollständig: Die Gesamtliste liess ein handelbares Paar aus (Aufzeichnung 03.11.2026: «husdt») — ein
    /// fehlendes Paar fragt die App einzeln.
    func bulkTickersURL(call: Int) -> String? { wazirxAPI + "/tickers/24hr" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Status «trading».
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("symbols").allObjects() where entry.optString("status") == "trading" {
            out.append(ExchangePair(try entry.string("baseAsset").uppercased(),
                                    try entry.string("quoteAsset").uppercased(), try entry.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// 24-h-Ticker (Zahlen als Text): `openPrice` = Kurs vor 24 h, `volume` in der Basiswährung. `at` ist die Zeit der
    /// Momentaufnahme, kein Handel — die Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("openPrice"))
    }
}
