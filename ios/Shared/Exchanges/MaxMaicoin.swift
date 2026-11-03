import Foundation

private let maxAPI = "https://max-api.maicoin.com/api/v3"

/// Sammelabfrage nur mit Auswahl: je Markt ein «markets[]=» (ohne Auswahl antwortet v3 mit einem Fehler).
private let maxTickers = maxAPI + "/tickers?"

/// Zeichen, die unverändert in die Adresse dürfen (MAX: Kleinbuchstaben und Ziffern, «btctwd»).
private let maxSafeCharacters = Set("abcdefghijklmnopqrstuvwxyz0123456789")

/// MAX (MaiCoin, Taiwan), öffentliche API v3: https://max.maicoin.com/documents/api — Kennung wie «btctwd» (klein).
/// Fehler als `{"success": false, "error": {"code", "message"}}`.
struct MaxMaicoin: SimpleExchange {
    let key: String = "MaxMaicoin"
    let name: String = "MAX"
    let pairListURL: String = maxAPI + "/markets"
    let tickerURL: String = maxAPI + "/ticker?market=%1$s"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).lowercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let error = json.optObject("error") else { try noErrorText() }
        return try error.string("message")
    }

    // MARK: Sammelabfrage: nur gefiltert

    var bulkTickersNumOfRequests: Int { 1 }

    /// Alle beobachteten Märkte in einer Anfrage. Ein unbekannter Markt lässt die ganze Anfrage scheitern; dann lädt
    /// die App die Paarliste, lässt Unbekannte weg und fragt neu (`bulkKnownPairsURL`, wie BTC Markets). Kennungen
    /// mit anderen Zeichen als `maxSafeCharacters` bleiben draussen.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        guard call == 0 else { return nil }
        let safe = pairIds.filter { id in !id.isEmpty && id.allSatisfy { maxSafeCharacters.contains($0) } }
        let ids = Set(safe).sorted()
        if ids.isEmpty { return nil }
        return maxTickers + ids.map { "markets%5B%5D=" + $0 }.joined(separator: "&")
    }

    func bulkKnownPairsURL(call: Int) -> String? { pairListAddress(call: call) }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("market"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Status «active».
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try JArray(string: body).allObjects() where market.optString("status") == "active" {
            out.append(ExchangePair(try market.string("base_unit").uppercased(),
                                    try market.string("quote_unit").uppercased(), try market.string("id")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text): `buy`/`sell` = bestes Gebot/Angebot, `open` = Kurs vor 24 h, `vol` in der
    /// Basis-, `vol_in_quote` in der Gegenwährung. `at` ist die Zeit der Momentaufnahme, kein Handel — die Zeit der
    /// Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.volQuote = row.optDoubleOrMissing("vol_in_quote")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }
}
