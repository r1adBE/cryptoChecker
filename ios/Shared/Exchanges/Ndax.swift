import Foundation

/// Alle Märkte in einer Antwort (ein Objekt je «BTC_CAD»); sie dient zugleich als Paarliste.
private let ndaxTicker = "https://core.ndax.io/v1/ticker"

/// NDAX (Kanada), öffentlicher Ticker im Poloniex-Format: https://apidoc.ndax.io/ — Kennung wie «BTC_CAD». Einzelkurs,
/// Sammelabfrage und Paarliste lesen dieselbe Gesamtliste. `percentChange` hat keinen dokumentierten Zeitraum (die App
/// rechnet mit Kerzen), `quoteVolume` gleicht `baseVolume` (Aufzeichnung 03.11.2026) und bleibt darum weg.
struct Ndax: SimpleExchange {
    let key: String = "Ndax"
    let name: String = "NDAX"
    let pairListURL: String = ndaxTicker
    let tickerURL: String = ndaxTicker

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "_" + info.quote.uppercased()
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = json.optObject(pairId(info) ?? ""), !Ndax.frozen(row) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    var bulkTickersComplete: Bool { true }

    /// Der Einzelkurs liest dieselbe Gesamtliste: «kein Eintrag» ist so verlässlich wie in der Sammelabfrage.
    var singleTickerReadsFullList: Bool { true }

    func bulkTickersURL(call: Int) -> String? { ndaxTicker }

    /// Eingefrorene Märkte (`isFrozen` «1») fallen still weg und gelten als nicht gehandelt.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).forEachObject { id, row in
            guard Ndax.split(id) != nil, !Ndax.frozen(row) else { return }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Eingefrorene Märkte und solche ohne Kurs (`last` 0, nie gehandelt, z. B. «USD_CAD») fallen weg.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        json.forEachObject { id, row in
            guard !Ndax.frozen(row), !BulkTickerRows.inactivePrice(row, "last"), let sides = Ndax.split(id) else { return }
            out.append(ExchangePair(sides.0, sides.1, id))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs, Gebot/Angebot, Hoch/Tief über 24 h und Volumen in der Basiswährung (Zahlen als Text); keine Zeit.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("highestBid")
        ticker.ask = row.optDoubleOrMissing("lowestAsk")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
    }

    private static func frozen(_ row: JObject) -> Bool { row.optString("isFrozen") == "1" }

    /// «BTC_CAD» → (BTC, CAD); andere Form → nil.
    private static func split(_ id: String) -> (String, String)? {
        let parts = id.uppercased().split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty }) else { return nil }
        return (parts[0], parts[1])
    }
}
