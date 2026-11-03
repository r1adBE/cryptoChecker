import Foundation

private let coinoneAPI = "https://api.coinone.co.kr/public/v2"

/// Coinone handelt nur gegen KRW; Paarliste und Sammelabfrage gelten diesem Markt.
private let coinoneQuote = "KRW"

/// Coinone (Südkorea), öffentliche API v2: https://docs.coinone.co.kr/ — Kennung wie «BTC-KRW», in der Adresse
/// umgekehrt («…/ticker_new/KRW/BTC»). Antworten tragen `result` («success»/«error»); Kurse unter `tickers`.
struct Coinone: SimpleExchange {
    let key: String = "Coinone"
    let name: String = "Coinone"
    let pairListURL: String = coinoneAPI + "/markets/" + coinoneQuote
    let tickerURL: String = coinoneAPI + "/ticker_new/%1$s"
    let errorField: String? = "error_msg"

    // MARK: Einzelkurs

    /// Kennung «BTC-KRW» → Pfad «KRW/BTC».
    func pairId(_ info: PairQuery) -> String? {
        let id = info.pairId ?? info.base.uppercased() + "-" + info.quote.uppercased()
        guard let dash = id.firstIndex(of: "-") else { return coinoneQuote + "/" + id }
        return String(id[id.index(after: dash)...]) + "/" + String(id[..<dash])
    }

    /// Liste mit dem einen Paar; leer = Paar unbekannt.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try json.array("tickers").optObject(0) else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    /// Nur `result` «error» trägt einen Fehlertext.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optString("result") != "error" { try noErrorText() }
        return try simpleErrorMessage(json)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Alle KRW-Paare in einer Antwort (Aufzeichnung 03.11.2026: 357 von 357 der Paarliste).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { coinoneAPI + "/ticker_new/" + coinoneQuote }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("tickers").allObjects() {
            bulkRowIfActive(Coinone.idOf(row), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Märkte mit `trade_status` 1 (Handel offen).
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try json.array("markets").allObjects() where market.optInt("trade_status", 0) == 1 {
            let base = try market.string("target_currency").uppercased()
            let quote = try market.string("quote_currency").uppercased()
            out.append(ExchangePair(base, quote, base + "-" + quote))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text, Werte über 24 h): `first` = Kurs vor 24 h, Gebot/Angebot aus der ersten
    /// Stufe von `best_bids`/`best_asks`, `target_volume` in der Basiswährung. `timestamp` ist die Zeit der
    /// Momentaufnahme, kein Handel — die Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optArray("best_bids")?.optObject(0)?.optDoubleOrMissing("price") ?? Ticker.missing
        ticker.ask = row.optArray("best_asks")?.optObject(0)?.optDoubleOrMissing("price") ?? Ticker.missing
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("target_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("first"))
    }

    /// «btc»/«krw» der Zeile → Kennung «BTC-KRW»; ohne Angaben leer (Zeile wird übergangen).
    private static func idOf(_ row: JObject) -> String {
        let base = row.optString("target_currency").uppercased()
        let quote = row.optString("quote_currency").uppercased()
        return base.isEmpty || quote.isEmpty ? "" : base + "-" + quote
    }
}
