import Foundation

/// Alle Märkte in einer Antwort; sie dient zugleich als Paarliste.
private let paribuTicker = "https://www.paribu.com/ticker"

/// Paribu schreibt die Lira «TL»; angezeigt und verglichen wird der ISO-Code.
private let paribuLira = "TL"
private let isoLira = "TRY"

/// Paribu (Türkei), öffentlicher Ticker: ein Objekt je Markt unter Schlüsseln wie «BTC_TL» (= Kennung).
/// Einzelkurs und Paarliste lesen dieselbe Gesamtliste.
struct Paribu: SimpleExchange {
    let key: String = "Paribu"
    let name: String = "Paribu"
    let pairListURL: String = paribuTicker
    let tickerURL: String = paribuTicker

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base + "_" + Paribu.paribuCode(info.quote)
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = json.optObject(pairId(info) ?? "") else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { paribuTicker }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        // Kein Objekt oder kein «COIN_GEGENWÄHRUNG»: keine Ticker-Zeile, still übergehen
        try JObject(string: body).forEachObject { name, row in
            guard Paribu.split(name) != nil else { return }
            bulkRowIfActive(name, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        json.forEachObject { name, _ in
            guard let sides = Paribu.split(name) else { return }
            let counter = sides.1
            out.append(ExchangePair(sides.0, counter == paribuLira ? isoLira : counter, name))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile: `highestBid`/`lowestAsk` = bestes Gebot/Angebot, Hoch/Tief über 24 h. Nicht übernommen: `volume`
    /// (Einheit nicht belegt), `percentChange` (Zeitraum nicht belegt, die App rechnet dann mit Kerzen); eine Zeit
    /// des letzten Handels liefert Paribu nicht.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("highestBid")
        ticker.ask = row.optDoubleOrMissing("lowestAsk")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
    }

    /// «BTC_TL» → (BTC, TL); andere Form → nil.
    private static func split(_ name: String) -> (String, String)? {
        let parts = name.uppercased().split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty }) else { return nil }
        return (parts[0], parts[1])
    }

    private static func paribuCode(_ quote: String) -> String {
        quote.uppercased() == isoLira ? paribuLira : quote.uppercased()
    }
}
