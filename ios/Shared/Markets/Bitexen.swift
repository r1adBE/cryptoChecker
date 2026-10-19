import Foundation

/// Alle Märkte in einer Antwort; sie dient zugleich als Paarliste.
private let bitexenTicker = "https://www.bitexen.com/api/v1/ticker/"

/// Interner Grund, wenn `status` nicht «success» ist (angezeigt wird der Fehlertext der Börse, falls vorhanden).
private let bitexenNotSuccess = "Status not success"

/// Bitexen (Türkei, TRY-Paare), öffentliche API v1. Kennung = `market_code` («BTCTRY»). Antwort
/// {"status": "success", "data": {"ticker": {"BTCTRY": {…}}}}; Einzelkurs und Paarliste lesen dieselbe Gesamtliste.
final class Bitexen: SimpleMarket {
    init() {
        super.init(
            key: "Bitexen",
            name: "Bitexen",
            pairListURL: bitexenTicker,
            tickerURL: bitexenTicker,
            errorField: "message"
        )
    }

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try Bitexen.rows(json).optObject(pairId(info) ?? "") else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { bitexenTicker }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        // Werte, die keine Objekte sind, sind keine Ticker-Zeilen und fallen still weg
        try Bitexen.rows(JObject(string: body)).forEachObject { code, row in
            bulkRowIfActive(code, row: row, priceField: "last_price", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Coin und Gegenwährung aus dem Unterobjekt `market`; Zeilen ohne beides fallen weg.
    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        try Bitexen.rows(json).forEachObject { code, row in
            guard let market = row.optObject("market") else { return }
            out.append(MarketPair(market.optString("base_currency_code").uppercased(),
                                  market.optString("counter_currency_code").uppercased(), code))
        }
        return out
    }

    // MARK: Lesen

    /// Die Zeilen unter data.ticker. Jeder andere Status als «success» ist ein Fehler — nicht «keine Daten», sonst
    /// gälte jedes Paar als nicht mehr gehandelt.
    private static func rows(_ json: JObject) throws -> JObject {
        guard json.optString("status") == "success" else { throw JSONError(message: bitexenNotSuccess) }
        return try json.object("data").object("ticker")
    }

    /// Eine Ticker-Zeile (Zahlen als Text), `volume_24h` in der Basiswährung. Nicht übernommen: `change_24h` (Einheit
    /// und Zeitraum nicht verlässlich belegt, die App rechnet dann mit Kerzen) und `timestamp` (Momentaufnahme, kein
    /// Handel).
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high_24h")
        ticker.low = row.optDoubleOrMissing("low_24h")
        ticker.vol = row.optDoubleOrMissing("volume_24h")
    }
}
