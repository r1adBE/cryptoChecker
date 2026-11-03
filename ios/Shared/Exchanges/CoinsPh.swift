import Foundation

private let coinsPhAPI = "https://api.pro.coins.ph/openapi"

/// 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle.
private let coinsPhDayTicker = coinsPhAPI + "/quote/v1/ticker/24hr"

/// Coins.ph (Philippinen), öffentliche API im Binance-ähnlichen Format: https://coins-docs.github.io/rest-api/ —
/// Kennung wie «BTCPHP». Anders als bei Binance ist `priceChangePercent` ein Anteil (−0.0268 = −2,68 %), darum zählt
/// `openPrice`.
struct CoinsPh: SimpleExchange {
    let key: String = "CoinsPh"
    let name: String = "Coins.ph"
    let pairListURL: String = coinsPhAPI + "/v1/exchangeInfo"
    let tickerURL: String = coinsPhDayTicker + "?symbol=%1$s"
    let errorField: String? = "msg"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base.uppercased() + info.quote.uppercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Der Ticker ohne `symbol` führt jedes handelbare Paar (Aufzeichnung 03.11.2026: 95 von 95).
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { coinsPhDayTicker }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Status «trading» (klein geschrieben); «break» hat keinen laufenden Handel.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("symbols").allObjects()
        where entry.optString("status").uppercased() == "TRADING" {
            out.append(ExchangePair(try entry.string("baseAsset").uppercased(),
                                    try entry.string("quoteAsset").uppercased(),
                                    try entry.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile des 24-h-Tickers (gleitende 24 h: `openTime` bis `closeTime`). `openPrice` = Kurs vor 24 h;
    /// `closeTime` ist das Fensterende, kein Handel — die Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("openPrice"))
    }
}
