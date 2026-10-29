import Foundation

private let oneTradingAPI = "https://api.onetrading.com/fast/v1"

/// Alle Kurse auf einmal: Sammelabfrage und Ausweg des Einzelkurses.
private let oneTradingTickers = oneTradingAPI + "/market-ticker"

/// Instrumenttyp und Buchzustand, wie in «instruments» und «market-ticker».
private let oneTradingSpot = "SPOT"
private let oneTradingActive = "ACTIVE"

/// One Trading (EU, vormals Bitpanda Pro), nur Spot. Doku: docs.onetrading.com
struct OneTrading: SimpleExchange {
    let key: String = "OneTrading"
    let name: String = "One Trading"
    let pairListURL: String = oneTradingAPI + "/instruments"
    let tickerURL: String = oneTradingAPI + "/market-ticker/%1$s"
    let errorField: String? = "error"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "_" + info.quote }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    /// Einzelkurs mit Serverfehler (HTTP 5xx, etwa seit Oktober 2026): Kurs aus der Sammelliste.
    func tickerFallbackAddress(call: Int, info: PairQuery) -> String? { oneTradingTickers }

    /// Die Zeile des Paars aus der Sammelliste, Zeilen anderer Art (Futures) zählen nicht — wie `parseBulkTickers`.
    /// Fehlt sie: `JSONError.noData`; die App behält dann den ursprünglichen Serverfehler.
    func fillTickerFallback(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let id = pairId(info)
        let rows = try JArray(string: body).allObjects()
        guard let row = rows.first(where: { !OneTrading.isOtherType($0) && $0.optString("instrument_code") == id }) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { oneTradingTickers }

    /// Die Sammelliste enthält auch Futures («type» DATED_FUTURE, EQUITY_FUTURE …): nur Spot-Zeilen zählen. Zeilen
    /// ohne «type» werden gelesen (Art unbekannt), machen die Antwort aber unvollständig (`bulkResponseComplete`).
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
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
    func bulkResponseComplete(call: Int, body: String) -> Bool {
        guard let rows = try? JArray(string: body).allObjects() else { return false }
        return rows.allSatisfy { row in
            let type = OneTrading.typeOf(row)
            return !type.isEmpty && (type != oneTradingSpot || row.optString("state") == oneTradingActive)
        }
    }

    // MARK: Paarliste

    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for inst in try JArray(string: body).allObjects() {
            let activeSpot = inst.optString("type") == oneTradingSpot && inst.optString("state") == oneTradingActive
            guard activeSpot else { continue }
            out.append(ExchangePair(OneTrading.codeOf(inst, "base"), OneTrading.codeOf(inst, "quote"),
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
