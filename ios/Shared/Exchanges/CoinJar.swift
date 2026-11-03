import Foundation

private let coinJarData = "https://data.exchange.coinjar.com"

/// CoinJar Exchange (Australien/UK), öffentliche API: https://docs.exchange.coinjar.com/ — Kennung = Produkt-ID, teils
/// «BTCAUD», teils «BTC-USDC» (wie die Börse sie führt). `change_24h` ist ein Anteil über gleitende 24 h (−0.0152 =
/// −1,52 %; er wandert mit der Zeit und passt zum ersten Wert von `sparklines_24h`).
struct CoinJar: SimpleExchange {
    let key: String = "CoinJar"
    let name: String = "CoinJar"
    let pairListURL: String = "https://api.exchange.coinjar.com/products"
    let tickerURL: String = coinJarData + "/products/%1$s/ticker"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? (info.base + info.quote).uppercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    /// `{"error_type", "error_messages": [...]}`: die erste Meldung.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        let text = json.optArray("error_messages")?.optString(0) ?? ""
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try noErrorText() }
        return text
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Ein Objekt je Produkt-ID (Aufzeichnung 03.11.2026: 334 von 334 der Paarliste). Noch nie gehandelte Produkte
    /// führen `last` als `false` (36 davon): sie fallen still weg; die Einzelabfrage liefert dann keinen Kurs.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { coinJarData + "/tickers" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).forEachObject { id, row in
            if let flag = row.raw["last"] as? NSNumber, CFGetTypeID(flag) == CFBooleanGetTypeID() { return }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for product in try JArray(string: body).allObjects() {
            out.append(ExchangePair(try product.object("base_currency").string("iso_code").uppercased(),
                                    try product.object("counter_currency").string("iso_code").uppercased(),
                                    try product.string("id")))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs, Gebot/Angebot, `volume_24h` in der Basiswährung, `change_24h` als Anteil. Nicht übernommen: `prev_close`
    /// (Schluss einer Handelssitzung, nicht vor 24 h) und `volume` (ganze Sitzung); `current_time` ist keine Handelszeit.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("volume_24h")
        ticker.change24hPercent = Change24h.fraction(row.optDouble("change_24h"))
    }
}
