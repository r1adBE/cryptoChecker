import Foundation

private let latokenAPI = "https://api.latoken.com/v2"

/// LATOKEN (API v2). Paare und Ticker kennen nur Währungs-IDs (UUID); die Kürzel liefert die Währungsliste — darum
/// zwei gemeinsam gelesene Paarlisten statt einer (`pairListCombined`) und kein `SimpleExchange`.
/// Paar-Kennung: «Basis-ID/Quote-ID». Vorsicht: «amount24h» = Menge im Coin, «volume24h» = Gegenwert.
struct Latoken: Exchange {
    let key: String = "Latoken"
    let name: String = "LATOKEN"
    let ttsName: String = "La token"

    // MARK: Einzelkurs

    func tickerAddress(call: Int, info: PairQuery) -> String {
        let pairId = info.pairId ?? Latoken.idOf(info.base, info.quote)
        return latokenAPI + "/ticker/" + pairId
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try Latoken.copyRow(json, into: &ticker)
    }

    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.string("message")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    func bulkTickersURL(call: Int) -> String? { latokenAPI + "/ticker" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            let baseId = row.optString("baseCurrency")
            let quoteId = row.optString("quoteCurrency")
            if baseId.isEmpty || quoteId.isEmpty { continue }
            bulkRowIfActive(Latoken.idOf(baseId, quoteId), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try Latoken.copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste: Währungen (0) und Paare (1) zusammen auswerten

    var pairListCallCount: Int { 2 }

    var pairListCombined: Bool { true }

    func pairListAddress(call: Int) -> String? {
        call == 0 ? latokenAPI + "/currency" : latokenAPI + "/pair"
    }

    func fillPairListCombined(bodies: [String]) throws -> [ExchangePair] {
        let tagById = try Latoken.currencyTags(bodies[0])
        var out: [ExchangePair] = []
        for pair in try JArray(string: bodies[1]).allObjects() where pair.optString("status") == "PAIR_STATUS_ACTIVE" {
            let baseId = pair.optString("baseCurrency")
            let quoteId = pair.optString("quoteCurrency")
            // Unbekannte ID: Paar weglassen
            guard let baseTag = tagById[baseId], let quoteTag = tagById[quoteId] else { continue }
            out.append(ExchangePair(baseTag, quoteTag, Latoken.idOf(baseId, quoteId)))
        }
        return out
    }

    // MARK: Namen

    /// Volle Namen stehen in derselben Währungsliste (Kürzel «tag» → «name»).
    func parseBaseNamesCombined(bodies: [String]) throws -> [String: String] {
        var byTag: [String: String] = [:]
        for currency in try JArray(string: bodies[0]).allObjects() {
            let tag = currency.optString("tag")
            let fullName = currency.optString("name")
            if tag.isEmpty || fullName.isEmpty || byTag[tag] != nil { continue }
            byTag[tag] = fullName
        }
        return byTag
    }

    // MARK: Lesen

    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bestBid")
        ticker.ask = row.optDoubleOrMissing("bestAsk")
        ticker.vol = row.optDoubleOrMissing("amount24h")
        ticker.volQuote = row.optDoubleOrMissing("volume24h")
        // «updateTimestamp» ist die Aktualisierung der Übersicht, kein Handel: Zeit der Börse unbekannt (sonst sähe ein
        // stehender Kurs frisch aus)
        // change24h: Prozent über 24 h
        ticker.change24hPercent = Change24h.percent(row.optDouble("change24h"))
    }

    /// Währungs-ID → Kürzel; Einträge ohne ID oder Kürzel fallen weg.
    private static func currencyTags(_ body: String) throws -> [String: String] {
        var tags: [String: String] = [:]
        for currency in try JArray(string: body).allObjects() {
            let id = currency.optString("id")
            let tag = currency.optString("tag")
            if !id.isEmpty && !tag.isEmpty { tags[id] = tag }
        }
        return tags
    }

    private static func idOf(_ base: String, _ quote: String) -> String { base + "/" + quote }
}
