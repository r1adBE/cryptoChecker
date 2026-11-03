import Foundation

private let binanceThAPI = "https://api.binance.th/api/v1"

/// Binance TH (Gulf Binance, Thailand), öffentliche API v1 im Binance-Format: https://www.binance.th/api-docs/en/ —
/// Kennung wie «BTCTHB». Der 24-h-Ticker verlangt ein Symbol (ohne Symbol HTTP 400), darum keine Sammelabfrage.
struct BinanceTh: SimpleExchange {
    let key: String = "BinanceTh"
    let name: String = "Binance TH"
    let pairListURL: String = binanceThAPI + "/exchangeInfo"
    let tickerURL: String = binanceThAPI + "/ticker/24hr?symbol=%1$s"
    let errorField: String? = "msg"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base.uppercased() + info.quote.uppercased() }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Paarliste

    /// Nur Status «TRADING» (wie Binance).
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("symbols").allObjects() where entry.optString("status") == "TRADING" {
            out.append(ExchangePair(try entry.string("baseAsset"), try entry.string("quoteAsset"),
                                    try entry.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Der 24-h-Ticker wie bei Binance: `priceChangePercent` über gleitende 24 h, bereits Prozent. `closeTime` ist
    /// bloss das Fensterende, kein Handel — die Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }
}
