import Foundation

private let lunoAPI = "https://api.luno.com"

/// Luno führt Bitcoin als «XBT»; angezeigt und verglichen wird das übliche Kürzel.
private let lunoCommon: [String: String] = ["XBT": "BTC"]

/// Status, bei denen ein Markt gehandelt wird. POST_ONLY (Ticker: «POSTONLY») nimmt nur Limit-Aufträge an, der
/// Kurs bleibt aber gültig; DISABLED zeigt bloss den letzten Handel vor der Sperre.
private let lunoTrading: Set<String> = ["ACTIVE", "POST_ONLY", "POSTONLY"]

/// Luno (Südafrika, Nigeria, Malaysia u. a.), öffentliche API: https://www.luno.com/en/developers/api — Kennung
/// wie «XBTZAR». Ticker ohne Hoch/Tief und ohne 24-h-Veränderung (die App rechnet mit Kerzen).
final class Luno: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Luno",
            name: "Luno",
            pairListURL: lunoAPI + "/api/exchange/1/markets",
            tickerURL: lunoAPI + "/api/1/ticker?pair=%1$s",
            errorField: "error"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? Luno.lunoCode(info.base) + Luno.lunoCode(info.quote)
    }

    /// Gesperrter Markt: kein Kurs (sonst stünde der letzte Handel vor der Sperre als aktuell da).
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard Luno.isTrading(json) else { throw JSONError(message: JSONError.noData) }
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// `tickers` führt jeden handelbaren Markt (Aufzeichnung 19.10.2026: 147 von 147 Märkten der Paarliste),
    /// ein fehlendes Paar ist nicht mehr gelistet.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { lunoAPI + "/api/1/tickers" }

    /// Gesperrte Märkte fallen still weg und gelten damit als nicht mehr gehandelt (`bulkTickersComplete`).
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("tickers").allObjects() where Luno.isTrading(row) {
            bulkRowIfActive(row.optString("pair"), row: row, priceField: "last_trade", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for market in try json.array("markets").allObjects()
        where lunoTrading.contains(market.optString("trading_status")) {
            out.append(MarketPair(Luno.common(try market.string("base_currency")),
                                  Luno.common(try market.string("counter_currency")),
                                  try market.string("market_id")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile. Preise und Volumen kommen als Text; `rolling_24_hour_volume` zählt in der Basiswährung.
    /// `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_trade")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("rolling_24_hour_volume")
    }

    /// Ohne Statusfeld (oder JSON null) gilt der Markt als gehandelt; Fehlerantworten tragen keinen Status.
    private static func isTrading(_ row: JObject) -> Bool {
        row.isNull("status") || lunoTrading.contains(row.optString("status"))
    }

    private static func common(_ code: String) -> String {
        let upper = code.uppercased()
        return lunoCommon[upper] ?? upper
    }

    /// Umkehrung von `common` für die Kennung ohne gespeicherte Paar-ID.
    private static func lunoCode(_ code: String) -> String {
        let upper = code.uppercased()
        return lunoCommon.first { $0.value == upper }?.key ?? upper
    }
}
