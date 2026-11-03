import Foundation

/// Alle öffentlichen Kurse in einer Antwort; sie dient auch als Paarliste.
private let coinSpotLatest = "https://www.coinspot.com.au/pubapi/v2/latest"

/// Ohne «_GEGENWÄHRUNG» im Schlüssel gilt der Kurs in AUD.
private let coinSpotDefaultQuote = "AUD"

/// Alte Kürzel der Börse → übliches Kürzel (Stellar hiess «STR», NEO «AntShares»).
private let coinSpotCommon: [String: String] = ["STR": "XLM", "ANS": "NEO"]

/// CoinSpot (Australien), öffentliche API v2: https://www.coinspot.com.au/v2/api — nur Gebot, Angebot und letzter
/// Kurs einer kleinen Auswahl von Coins (rund 16, Schlüssel «btc» in AUD, «btc_usdt» in USDT), ohne 24-h-Veränderung,
/// Hoch/Tief, Volumen und Zeit der Börse (die App rechnet die Veränderung mit Kerzen). Einzelkurs, Sammelabfrage und
/// Paarliste lesen dieselbe Gesamtliste; Kennung = Schlüssel der Liste.
struct CoinSpot: SimpleExchange {
    let key: String = "CoinSpot"
    let name: String = "CoinSpot"
    let pairListURL: String = coinSpotLatest
    let tickerURL: String = coinSpotLatest

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? CoinSpot.idFor(info.base, info.quote) }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = json.optObject("prices")?.optObject(pairId(info) ?? "") else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    /// `status` «error» mit `message`; «ok» ist kein Fehler.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optString("status") == "ok" { try noErrorText() }
        return try json.string("message")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    var bulkTickersComplete: Bool { true }

    /// Der Einzelkurs liest dieselbe Gesamtliste: «kein Eintrag» ist so verlässlich wie in der Sammelabfrage.
    var singleTickerReadsFullList: Bool { true }

    func bulkTickersURL(call: Int) -> String? { coinSpotLatest }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        try JObject(string: body).object("prices").forEachObject { id, row in
            guard CoinSpot.split(id) != nil else { return }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        try json.object("prices").forEachObject { id, _ in
            guard let sides = CoinSpot.split(id) else { return }
            out.append(ExchangePair(coinSpotCommon[sides.0] ?? sides.0, sides.1, id))
        }
        return out
    }

    // MARK: Lesen

    /// Kurs, Gebot und Angebot (Text); ein Gebot «NaN» (leeres Orderbuch) gilt als nicht geliefert.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = CoinSpot.finiteOrMissing(row.optDouble("bid"))
        ticker.ask = CoinSpot.finiteOrMissing(row.optDouble("ask"))
    }

    private static func finiteOrMissing(_ value: Double) -> Double { value.isFinite ? value : Ticker.missing }

    /// «btc» → (BTC, AUD), «btc_usdt» → (BTC, USDT); andere Form → nil.
    private static func split(_ id: String) -> (String, String)? {
        let parts = id.uppercased().split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        if parts.contains(where: { $0.isEmpty }) { return nil }
        switch parts.count {
        case 1: return (parts[0], coinSpotDefaultQuote)
        case 2: return (parts[0], parts[1])
        default: return nil
        }
    }

    /// Umkehrung für ein Paar ohne gespeicherte Kennung: XLM/AUD → «str», BTC/USDT → «btc_usdt».
    private static func idFor(_ base: String, _ quote: String) -> String {
        let upper = base.uppercased()
        let coin = (coinSpotCommon.first { $0.value == upper }?.key ?? upper).lowercased()
        return quote.uppercased() == coinSpotDefaultQuote ? coin : coin + "_" + quote.lowercased()
    }
}
