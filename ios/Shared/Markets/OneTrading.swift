import Foundation

private let oneTradingAPI = "https://api.onetrading.com/fast/v1"

/// Instrumenttyp und Buchzustand, wie in «instruments» und «market-ticker».
private let oneTradingSpot = "SPOT"
private let oneTradingActive = "ACTIVE"

/// One Trading (EU, vormals Bitpanda Pro), nur Spot. Doku: docs.onetrading.com
final class OneTrading: SimpleMarket {
    init() {
        super.init(
            key: "OneTrading",
            name: "One Trading",
            pairListURL: oneTradingAPI + "/instruments",
            tickerURL: oneTradingAPI + "/market-ticker/%1$s",
            errorField: "error"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "_" + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { oneTradingAPI + "/market-ticker" }

    /// Die Sammelliste enthält auch Futures («type» DATED_FUTURE, EQUITY_FUTURE …): nur Spot-Zeilen zählen. Zeilen
    /// ohne «type» werden gelesen (Art unbekannt), machen die Antwort aber unvollständig (`bulkResponseComplete`).
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() where !OneTrading.isOtherType(row) {
            bulkRowIfActive(row.optString("instrument_code"), row: row, priceField: "last_price", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    /// Vollständig nur nach den Spot-Zeilen: Jede Zeile hat eine Art, und jedes Spot-Buch ist ACTIVE. Zeilen anderer Art
    /// (Futures) fallen weg, ohne dass ein Spot-Paar fehlen könnte. Fehlt die Art einer Zeile oder ist ein Spot-Buch nicht
    /// ACTIVE (etwa CLOSED), kann ein fehlendes Paar auch nur vorübergehend fehlen: dann entscheidet die Einzelabfrage
    /// statt «nicht mehr gehandelt».
    override func bulkResponseComplete(call: Int, body: String) -> Bool {
        guard let rows = try? JArray(string: body).allObjects() else { return false }
        return rows.allSatisfy { row in
            let type = OneTrading.typeOf(row)
            return !type.isEmpty && (type != oneTradingSpot || row.optString("state") == oneTradingActive)
        }
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for inst in try JArray(string: body).allObjects() {
            let activeSpot = inst.optString("type") == oneTradingSpot && inst.optString("state") == oneTradingActive
            guard activeSpot else { continue }
            out.append(MarketPair(OneTrading.codeOf(inst, "base"), OneTrading.codeOf(inst, "quote"),
                                        try inst.string("id")))
        }
        return out
    }

    // MARK: Lesen

    /// Zeile eines anderen Instrumenttyps (Futures); ohne «type» nicht (Art unbekannt).
    private static func isOtherType(_ row: JObject) -> Bool {
        let type = typeOf(row)
        return !type.isEmpty && type != oneTradingSpot
    }

    /// «type» der Zeile; fehlend, JSON-null und der Text «null» gelten gleich als keine Art (leer) — wie unter Android,
    /// wo optString für JSON-null den Text «null» liefert.
    private static func typeOf(_ row: JObject) -> String {
        let type = row.optString("type")
        return type == "null" ? "" : type
    }

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        // Ohne Zeitangabe der Börse; «price_change_percentage» = Prozent über 24 h
        ticker.change24hPercent = Change24h.percent(row.optDouble("price_change_percentage"))
    }

    /// «code» des Unterobjekts `side` («base»/«quote»); fehlt es: leer (Paar fällt dann weg).
    private static func codeOf(_ inst: JObject, _ side: String) -> String {
        inst.optObject(side)?.optString("code") ?? ""
    }
}
