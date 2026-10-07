import Foundation

/// HTX (früher Huobi) Spot.
final class Htx: SimpleMarket {
    init() {
        super.init(
            key: "Htx",
            name: "HTX",
            pairsURL: "https://api.huobi.pro/v2/settings/common/symbols",
            tickerURL: "https://api.huobi.pro/market/detail/merged?symbol=%1$s",
            errorPropertyName: "err-msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            if item.optString("state") != "online" { continue }
            try pairs.append(CurrencyPairInfo(
                item.string("bc").uppercased(),
                item.string("qc").uppercased(),
                item.string("sc")
            ))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try HtxTicker.read(json.object("tick"), &ticker)
        ticker.timestamp = json.optLong("ts")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.huobi.pro/market/tickers" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        try HtxTicker.readAll(response, arrayName: "data", idName: "symbol")
    }

    override var bulkTickersComplete: Bool { true }
}

/// HTX USDT-Perpetuals (linear swap).
final class HtxFutures: SimpleMarket {
    init() {
        super.init(
            key: "HtxFutures",
            name: "HTX Futures",
            pairsURL: "https://api.hbdm.com/linear-swap-api/v1/swap_contract_info?business_type=swap",
            tickerURL: "https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=%1$s",
            errorPropertyName: "err_msg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.array("data").allObjects() {
            // contract_status 1 = gelistet / handelbar
            if item.optInt("contract_status", -1) != 1 { continue }
            let code = try item.string("contract_code")          // z. B. BTC-USDT
            let parts = code.components(separatedBy: "-")
            if parts.count != 2 { continue }
            pairs.append(CurrencyPairInfo(parts[0], parts[1], code, .perpetual))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let tick = try json.object("tick")
        try HtxTicker.read(tick, &ticker)
        ticker.volQuote = tick.optDoubleNoData("trade_turnover")
        ticker.timestamp = json.optLong("ts")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? {
        "https://api.hbdm.com/linear-swap-ex/market/detail/batch_merged?business_type=swap"
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        try HtxTicker.readAll(response, arrayName: "ticks", idName: "contract_code")
    }

    override var bulkTickersComplete: Bool { true }
}

/// Spot und Swap nutzen dieselben Feldnamen; bid/ask mal als Zahl, mal als [Preis, Menge].
enum HtxTicker {
    static func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("close")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("amount")
        let turnover = json.optDoubleNoData("trade_turnover")
        ticker.volQuote = turnover >= 0 ? turnover : json.optDoubleNoData("vol")
        ticker.bid = priceOf(json, "bid")
        ticker.ask = priceOf(json, "ask")
        let ts = json.optLong("ts")
        if ts > 0 { ticker.timestamp = ts }
    }

    private static func priceOf(_ json: JObject, _ name: String) -> Double {
        if let array = json.optArray(name) { return array.optDouble(0, Ticker.noData) }
        return json.optDoubleNoData(name)
    }

    static func readAll(_ response: String, arrayName: String, idName: String) throws -> [String: Ticker] {
        let root = try JObject(string: response)
        let time = root.optLong("ts")
        var tickers: [String: Ticker] = [:]
        for item in try root.array(arrayName).allObjects() {
            let id = item.optString(idName)
            if id.isEmpty { continue }
            var ticker = Ticker()
            ticker.timestamp = time
            do { try read(item, &ticker) } catch { continue }
            tickers[id] = ticker
        }
        return tickers
    }
}
