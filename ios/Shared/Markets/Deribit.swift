import Foundation

/// Deribit — nur Perpetuals: BTC-PERPETUAL/ETH-PERPETUAL (gegen USD, in Coins
/// abgerechnet) und die linearen USDC-Perpetuals (z. B. SOL_USDC-PERPETUAL).
/// API v2: https://docs.deribit.com
final class Deribit: Market {
    private static let base = "https://www.deribit.com/api/v2"
    private static let currencies = ["BTC", "ETH", "USDC"]

    init() { super.init(key: "Deribit", name: "Deribit") }

    override var currencyPairsNumOfRequests: Int { Deribit.currencies.count }

    override func currencyPairsURL(requestId: Int) -> String? {
        "\(Deribit.base)/public/get_instruments?currency=\(Deribit.currencies[requestId])&kind=future&expired=false"
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("result").allObjects() {
            if item.optString("settlement_period") != "perpetual" { continue }
            let state = item.optString("state")
            if !state.isEmpty && state != "open" { continue }
            try pairs.append(CurrencyPairInfo(item.string("base_currency"), item.string("quote_currency"),
                                              item.string("instrument_name"), .perpetual))
        }
        return pairs
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "\(Deribit.base)/public/ticker?instrument_name=\(info.pairId ?? "\(info.base)-PERPETUAL")"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let result = try json.object("result")
        ticker.last = try result.double("last_price")
        ticker.bid = result.optDoubleNoData("best_bid_price")
        ticker.ask = result.optDoubleNoData("best_ask_price")
        ticker.timestamp = result.optLong("timestamp")
        if let stats = result.optObject("stats") {
            ticker.high = stats.optDoubleNoData("high")
            ticker.low = stats.optDoubleNoData("low")
            ticker.vol = stats.optDoubleNoData("volume")
            ticker.volQuote = stats.optDoubleNoData("volume_usd")
            // price_change = gleitende 24 h in Prozent
            ticker.change24hPercent = Change24h.percent(stats.optDouble("price_change"))
        }
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.object("error").string("message")
    }

    // Sammelabfrage je Abrechnungswährung
    override var bulkTickersNumOfRequests: Int { Deribit.currencies.count }

    override func bulkTickersURL(requestId: Int) -> String? {
        "\(Deribit.base)/public/get_book_summary_by_currency?currency=\(Deribit.currencies[requestId])&kind=future"
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JObject(string: response).array("result").allObjects() {
            let name = item.optString("instrument_name")
            if !name.hasSuffix("PERPETUAL") { continue }
            let last = item.optDouble("last")
            if last.isNaN { continue }
            var t = Ticker()
            t.last = last
            t.bid = item.optDoubleNoData("bid_price")
            t.ask = item.optDoubleNoData("ask_price")
            t.high = item.optDoubleNoData("high")
            t.low = item.optDoubleNoData("low")
            t.vol = item.optDoubleNoData("volume")
            let usd = item.optDouble("volume_usd")
            t.volQuote = usd.isNaN ? item.optDoubleNoData("volume_notional") : usd
            t.timestamp = item.optLong("creation_timestamp")
            t.change24hPercent = Change24h.percent(item.optDouble("price_change"))
            tickers[name] = t
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
