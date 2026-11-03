import Foundation

private let gmoAPI = "https://api.coin.z.com/public/v1"

/// Spot-Kurse gelten in Yen.
private let gmoQuote = "JPY"

/// GMO Coin (Japan), öffentliche API: https://api.coin.z.com/docs/ — nur Spot: Kennung = Kürzel («BTC», gegen JPY);
/// Symbole mit «_» («BTC_JPY») sind Margin-Handel und bleiben weg. Antworten `{"status": 0, "data": …}`; ohne
/// 24-h-Veränderung (die App rechnet mit Kerzen), `timestamp` ist die Momentaufnahme.
struct GmoCoin: SimpleExchange {
    let key: String = "GmoCoin"
    let name: String = "GMO Coin"
    let pairListURL: String = gmoAPI + "/symbols"
    let tickerURL: String = gmoAPI + "/ticker?symbol=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base.uppercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        // Status ≠ 0 (Fehler, Wartung): kein «kein Eintrag» — den Text liefert errorMessage
        let status = json.optInt("status", -1)
        if status != 0 { throw JSONError(message: "GMO status \(status)") }
        guard let row = try json.array("data").optObject(0) else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    /// `status` ≠ 0 mit `messages[0].message_string` (z. B. «Not found», Wartung).
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("status", 0) == 0 { try noErrorText() }
        guard let message = json.optArray("messages")?.optObject(0) else { try noErrorText() }
        return try message.string("message_string")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Ohne `symbol` jedes Symbol (Aufzeichnung 03.11.2026: 33 von 33).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { gmoAPI + "/ticker" }

    /// Nur Spot (ohne «_»).
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            let id = row.optString("symbol")
            if id.contains("_") { continue }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Spot-Symbole (ohne «_») gegen JPY.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for row in try json.array("data").allObjects() {
            let id = try row.string("symbol")
            if id.isEmpty || id.contains("_") { continue }
            out.append(ExchangePair(id.uppercased(), gmoQuote, id))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs, Gebot/Angebot und Volumen (Basiswährung, 24 h). `high`/`low` bleiben weg: der Zeitraum ist in der Doku
    /// nicht als gleitende 24 h belegt (wie bei Upbit der Handelstag). Keine Handelszeit.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("volume")
    }
}
