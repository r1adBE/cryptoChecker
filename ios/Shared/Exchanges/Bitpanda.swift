import Foundation

/// Alle Kurse in einer Antwort (je Coin ein Objekt mit einem Preis je Fiat-Währung); sie dient auch als Paarliste.
private let bitpandaTicker = "https://api.bitpanda.com/v1/ticker"

/// Edelmetalle führt Bitpanda je Gramm (XAU/EUR rund 113 statt rund 3500 je Unze): nicht angeboten, sonst stünde unter
/// dem üblichen Kürzel ein anderer Massstab.
private let bitpandaMetals: Set<String> = ["XAU", "XAG", "XPT", "XPD"]

/// Bitpanda (Broker, Österreich; DE/AT/CH/IT/PL u. a.), öffentlicher Ticker ohne Schlüssel:
/// https://developers.bitpanda.com/ — nur der Momentkurs je Coin und Fiat-Währung, ohne Gebot/Angebot, Hoch/Tief,
/// Volumen und 24-h-Veränderung (die App rechnet sie mit Kerzen wie bei bitFlyer), ohne Zeit der Börse. Kennung
/// «BTC_EUR». Einzelkurs, Sammelabfrage und Paarliste lesen dieselbe Gesamtliste.
struct Bitpanda: SimpleExchange {
    let key: String = "Bitpanda"
    let name: String = "Bitpanda"
    let pairListURL: String = bitpandaTicker
    let tickerURL: String = bitpandaTicker

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "_" + info.quote.uppercased()
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let sides = Bitpanda.split(pairId(info) ?? ""), !bitpandaMetals.contains(sides.0),
              let row = json.optObject(sides.0), row.has(sides.1) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, fiat: sides.1, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    var bulkTickersComplete: Bool { true }

    /// Der Einzelkurs liest dieselbe Gesamtliste: «kein Eintrag» ist so verlässlich wie in der Sammelabfrage.
    var singleTickerReadsFullList: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bitpandaTicker }

    /// Preis 0 (Coin ohne Kurs in dieser Währung) fällt still weg; Edelmetalle nicht (je Gramm).
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).forEachObject { coin, row in
            if coin.isEmpty || bitpandaMetals.contains(coin) { return }
            for fiat in row.keys {
                bulkRowIfActive(coin + "_" + fiat, row: row, priceField: fiat, tickers: &tickers, failed: &failed) {
                    try copyRow(row, fiat: fiat, into: &$0)
                }
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Je Coin und Fiat-Währung mit Kurs über 0 ein Paar; Edelmetalle nicht (je Gramm, siehe oben).
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        json.forEachObject { coin, row in
            if coin.isEmpty || bitpandaMetals.contains(coin) { return }
            for fiat in row.keys {
                let price = row.optDouble(fiat)
                if fiat.isEmpty || !price.isFinite || price <= 0 { continue }
                out.append(ExchangePair(coin.uppercased(), fiat.uppercased(), coin + "_" + fiat))
            }
        }
        return out
    }

    // MARK: Lesen

    /// Nur der Kurs (Text); alle übrigen Felder bleiben «keine Angabe».
    private func copyRow(_ row: JObject, fiat: String, into ticker: inout Ticker) throws {
        ticker.last = try row.double(fiat)
    }

    /// «BTC_EUR» → (BTC, EUR); andere Form → nil.
    private static func split(_ pairId: String) -> (String, String)? {
        let parts = pairId.split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty }) else { return nil }
        return (parts[0], parts[1])
    }
}
