import Foundation

/// Bybit Spot. API v5: https://bybit-exchange.github.io/docs/v5/market/tickers
final class Bybit: BybitBase {
    init() { super.init(key: "Bybit", name: "Bybit", category: "spot", contractType: .none) }
}

/// Bybit USDT/USDC-Perpetuals (category=linear).
final class BybitFutures: BybitBase {
    init() { super.init(key: "BybitFutures", name: "Bybit Futures", category: "linear", contractType: .perpetual) }
}

class BybitBase: SimpleMarket {
    private let category: String
    private let contractType: FuturesContractType

    init(key: String, name: String, category: String, contractType: FuturesContractType) {
        self.category = category
        self.contractType = contractType
        super.init(
            key: key,
            name: name,
            // Linear hat über 500 Symbole; ohne limit kämen nur die ersten 500.
            pairsURL: "https://api.bybit.com/v5/market/instruments-info?category=\(category)&limit=1000",
            tickerURL: "https://api.bybit.com/v5/market/tickers?category=\(category)&symbol=%1$s",
            errorPropertyName: "retMsg"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.object("result").array("list").allObjects() {
            if item.optString("status") != "Trading" { continue }
            // Bei Futures nur Perpetuals; Laufzeit-Kontrakte haben andere Symbolnamen.
            if contractType == .perpetual && item.optString("contractType") != "LinearPerpetual" { continue }
            // Aktien, ETFs, Rohstoffe und Devisen (TradFi-Perps) nur bei Futures kennzeichnen; Spot-Token bleiben Token
            let tradFi: Bool? = contractType == .perpetual ? TradFi.bybit(symbolType: item.optString("symbolType")) : nil
            try pairs.append(CurrencyPairInfo(item.string("baseCoin"), item.string("quoteCoin"), item.string("symbol"), contractType,
                                              tradFi: tradFi))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let list = try json.object("result").array("list")
        if list.count < 1 { throw JSONError(message: "No data") }
        try readTicker(list.object(0), &ticker)
        ticker.timestamp = json.optLong("time")
    }

    /// retCode 0 = Erfolg: retMsg ist dann «OK», kein Fehlertext – es bleibt beim Fehler des Parsers.
    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        if json.optInt("retCode", -1) == 0 { throw JSONError(message: "No error text") }
        return try super.parseError(requestId: requestId, json: json, info: info)
    }

    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("lastPrice")
        ticker.bid = json.optDoubleNoData("bid1Price")
        ticker.ask = json.optDoubleNoData("ask1Price")
        ticker.high = json.optDoubleNoData("highPrice24h")
        ticker.low = json.optDoubleNoData("lowPrice24h")
        ticker.vol = json.optDoubleNoData("volume24h")
        ticker.volQuote = json.optDoubleNoData("turnover24h")
        // Gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(json.optDouble("price24hPcnt"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? {
        "https://api.bybit.com/v5/market/tickers?category=\(category)"
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        let json = try JObject(string: response)
        let time = json.optLong("time")
        var tickers: [String: Ticker] = [:]
        for item in try json.object("result").array("list").allObjects() {
            let symbol = item.optString("symbol")
            if symbol.isEmpty { continue }
            var ticker = Ticker()
            do { try readTicker(item, &ticker) } catch { continue }
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
        return tickers
    }

    /// Die Sammelabfrage liefert alle gehandelten Symbole der Kategorie.
    override var bulkTickersComplete: Bool { true }
}
