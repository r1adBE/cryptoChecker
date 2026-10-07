import Foundation

final class Binance: BinanceBase {
    init() { super.init(key: "Binance", name: "Binance", domain: "com") }
}

final class BinanceUs: BinanceBase {
    init() { super.init(key: "BinanceUs", name: "Binance.US", domain: "us") }
}

class BinanceBase: SimpleMarket {
    private let allTickersURL: String

    /// Ab 100 Symbolen kostet die Auswahl bei Binance gleich viel wie alle.
    private static let maxSymbolsPerRequest = 100

    init(key: String, name: String, domain: String) {
        allTickersURL = "https://api.binance.\(domain)/api/v3/ticker/24hr"
        super.init(
            key: key,
            name: name,
            pairsURL: "https://api.binance.\(domain)/api/v3/exchangeInfo",
            tickerURL: "https://api.binance.\(domain)/api/v3/ticker/24hr?symbol=%1$s",
            errorPropertyName: "msg"
        )
    }

    /// Ohne symbol-Parameter liefert derselbe Endpunkt alle Paare auf einmal.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { allTickersURL }

    /// Binance liefert ohne Filter jedes gehandelte Paar.
    override var bulkTickersComplete: Bool { true }

    /// Ohne Filter ~3500 Paare (rund 2 MB JSON); mit `symbols=[...]` nur die
    /// beobachteten Paare, wenige Kilobyte.
    override func bulkTickersURL(requestId: Int, pairIds: [String]) -> String? {
        if pairIds.isEmpty || pairIds.count > BinanceBase.maxSymbolsPerRequest { return allTickersURL }

        let symbols = "[" + pairIds.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        return "\(allTickersURL)?symbols=" + symbols.urlQueryEncoded
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for entry in try JArray(string: response).allObjects() {
            let symbol = entry.optString("symbol")
            if symbol.isEmpty { continue }

            var ticker = Ticker()
            try readTicker(entry, &ticker)
            tickers[symbol] = ticker
        }
        return tickers
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try readTicker(json, &ticker)
    }

    /// Einzelabruf und Massenabfrage liefern dieselbe Struktur je Paar.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.bid = try json.double("bidPrice")
        ticker.ask = try json.double("askPrice")

        ticker.vol = try json.double("volume")
        ticker.volQuote = try json.double("quoteVolume")

        ticker.high = try json.double("highPrice")
        ticker.low = try json.double("lowPrice")

        ticker.last = try json.double("lastPrice")
        ticker.timestamp = try json.long("closeTime")

        // Gleitende 24 h, schon in Prozent
        ticker.change24hPercent = Change24h.percent(json.optDouble("priceChangePercent"))
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for market in try json.array("symbols").allObjects() {
            if try market.string("status") != "TRADING" { continue }

            let symbol = try market.string("symbol")
            let baseAsset = try market.string("baseAsset")
            let quoteAsset = try market.string("quoteAsset")
            pairs.append(CurrencyPairInfo(baseAsset, quoteAsset, symbol))
        }
        return pairs
    }
}
