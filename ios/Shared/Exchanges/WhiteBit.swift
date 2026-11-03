import Foundation

private let whitebitAPI = "https://whitebit.com/api"

/// WhiteBIT (Europa/Ukraine), öffentliche API: https://docs.whitebit.com/ — Kennung wie «BTC_USDT». Einzelkurs über
/// v1 (`/public/ticker?market=`, mit Gebot/Angebot und Kurs vor 24 h), Sammelabfrage über v4 (alle Märkte, nur Kurs,
/// Volumen und Veränderung in Prozent).
struct WhiteBit: SimpleExchange {
    let key: String = "WhiteBit"
    let name: String = "WhiteBIT"
    let pairListURL: String = whitebitAPI + "/v4/public/markets"
    let tickerURL: String = whitebitAPI + "/v1/public/ticker?market=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "_" + info.quote.uppercased()
    }

    /// v1: `result` mit `open` (Kurs vor 24 h), Gebot/Angebot, Hoch/Tief, `volume` (Basis) und `deal` (Gegenwährung).
    /// Keine Zeit des letzten Handels.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let row = try json.object("result")
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("deal")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }

    /// `success` false: `message` als Text oder als Objekt mit einer Liste je Feld («market»: [«Market is not
    /// available.»]); dann zählt der erste Text in alphabetischer Feldfolge.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optBool("success", true) { try noErrorText() }
        if let fields = json.optObject("message") {
            for name in fields.keys.sorted() {
                let text = fields.optArray(name)?.optString(0) ?? ""
                if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }
            }
            try noErrorText()
        }
        let text = json.optString("message")
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try noErrorText() }
        return text
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// v4 führt jeden Markt der Paarliste (Aufzeichnung 03.11.2026: alle Spot-Märkte), dazu Futures.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { whitebitAPI + "/v4/public/ticker" }

    /// Objekt je Markt; eingefrorene Märkte (`isFrozen`) fallen still weg und gelten als nicht gehandelt.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).forEachObject { name, row in
            if row.optBool("isFrozen", false) { return }
            bulkRowIfActive(name, row: row, priceField: "last_price", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Liste aller Märkte: nur `type` «spot» mit offenem Handel (`tradesEnabled`); Futures und TradFi-Futures nicht.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try JArray(string: body).allObjects()
        where market.optString("type") == "spot" && market.optBool("tradesEnabled", false) {
            out.append(ExchangePair(try market.string("stock").uppercased(), try market.string("money").uppercased(),
                                    try market.string("name")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine v4-Zeile: Kurs, Volumen in Basis- und Gegenwährung, `change` = Veränderung über 24 h in Prozent.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("change"))
    }
}
