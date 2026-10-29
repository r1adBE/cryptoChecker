import Foundation

private let indodaxAPI = "https://indodax.com/api"

/// Indodax (Indonesien). Vorsicht: In der Paarliste ist `base_currency` die Gegenwährung (IDR/USDT) und
/// `traded_currency` der gehandelte Coin. https://github.com/btcid/indodax-official-api-docs
struct Indodax: SimpleExchange {
    let key: String = "Indodax"
    let name: String = "Indodax"
    let pairListURL: String = indodaxAPI + "/pairs"
    let tickerURL: String = indodaxAPI + "/ticker/%1$s"
    let errorField: String? = "error_description"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.baseLower + info.quoteLower }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("ticker"), into: &ticker, coin: info.baseLower, counter: info.quoteLower)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { indodaxAPI + "/ticker_all" }

    /// Schlüssel «btc_idr» → Paar-Kennung «btcidr».
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        // Werte, die keine Objekte sind, sind keine Ticker-Zeilen und fallen still weg
        try JObject(string: body).object("tickers").forEachObject { name, row in
            let sides = name.split(separator: "_").map(String.init)
            guard sides.count == 2 else { return }
            let coin = sides[0]
            let counter = sides[1]
            bulkRowIfActive(coin + counter, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0, coin: coin, counter: counter)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try JArray(string: body).allObjects() {
            if Indodax.isSet(entry, "is_maintenance") || Indodax.isSet(entry, "is_market_suspended") { continue }
            out.append(ExchangePair(
                try entry.string("traded_currency").uppercased(),
                try entry.string("base_currency").uppercased(),
                try entry.string("id")
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Ein Ticker; Volumen heissen «vol_<coin>» bzw. «vol_<gegenwährung>». `server_time` ist kein Handel.
    private func copyRow(_ row: JObject, into ticker: inout Ticker, coin: String, counter: String) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol_" + coin)
        ticker.volQuote = row.optDoubleOrMissing("vol_" + counter)
    }

    /// Schalter, der als true, 1, "1" oder "true" kommen kann.
    private static func isSet(_ entry: JObject, _ field: String) -> Bool {
        ["1", "true"].contains(entry.optString(field).lowercased())
    }
}
