import Foundation

private let bitkubAPI = "https://api.bitkub.com/api/v3/market"

/// Fehlercode 11 der API v3 = «Invalid symbol» (Doku, Abschnitt «Error Codes»).
private let bitkubInvalidSymbol = 11

/// Bitkub (Thailand), öffentliche API v3: https://github.com/bitkub/bitkub-official-api-docs (rest-v3.md) — Kennung
/// wie «BTC_THB». Ticker als Liste (mit `sym` ein Eintrag, ohne alle); Fehler als `{"error": <Code>}`.
struct Bitkub: SimpleExchange {
    let key: String = "Bitkub"
    let name: String = "Bitkub"
    let pairListURL: String = bitkubAPI + "/symbols"
    let tickerURL: String = bitkubAPI + "/ticker?sym=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "_" + info.quote.uppercased()
    }

    /// Liste mit dem einen Paar; leer = Paar unbekannt.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try JArray(string: body)
        guard !rows.isEmpty else { throw JSONError(message: JSONError.noData) }
        try copyRow(try rows.object(0), into: &ticker)
    }

    /// `{"error": 11}` = unbekanntes Paar («Invalid symbol», erkennt NotTraded); 0 = kein Fehler.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        let code = json.optInt("error", 0)
        if code == 0 { try noErrorText() }
        return code == bitkubInvalidSymbol ? "Invalid symbol" : "Bitkub error \(code)"
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Der Ticker ohne `sym` führt jedes Paar mit Status «active» (Aufzeichnung 03.11.2026: 362 von 362), ein
    /// fehlendes Paar ist eingestellt.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bitkubAPI + "/ticker" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Status «active»; «stopped» hat keinen Kurs mehr.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("result").allObjects() where market.optString("status") == "active" {
            out.append(ExchangePair(try market.string("base_asset").uppercased(),
                                    try market.string("quote_asset").uppercased(),
                                    try market.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text): Gebot/Angebot, Hoch/Tief über 24 h, Volumen in Basis- und Gegenwährung.
    /// Nicht übernommen: `percent_change` (Zeitraum nicht dokumentiert, die App rechnet mit Kerzen); eine Zeit des
    /// letzten Handels liefert Bitkub nicht.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        ticker.high = row.optDoubleOrMissing("high_24_hr")
        ticker.low = row.optDoubleOrMissing("low_24_hr")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
    }
}
