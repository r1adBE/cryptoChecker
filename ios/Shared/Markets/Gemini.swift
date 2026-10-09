import Foundation

/// Gemini Spot. Die Symbolliste enthält nur zusammengeschriebene Namen
/// (z. B. „btcusd“, „aavegusd“); Basis und Quote werden über bekannte
/// Quote-Endungen getrennt. Perpetuals („…perp“) sind ausgenommen.
final class Gemini: SimpleMarket {
    /// Längste zuerst, damit „gusd“ vor „usd“ greift.
    private static let quotes = ["rlusd", "gusd", "usdc", "usdt", "usd", "eur", "gbp", "sgd", "btc", "eth", "sol", "fil"]

    init() {
        super.init(
            key: "Gemini",
            name: "Gemini",
            pairsURL: "https://api.gemini.com/v1/symbols",
            tickerURL: "https://api.gemini.com/v1/pubticker/%1$s",
            errorPropertyName: "message"
        )
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        let list = try JArray(string: response)
        var pairs: [CurrencyPairInfo] = []
        for i in 0..<list.count {
            let symbol = try list.string(i).lowercased()
            if symbol.hasSuffix("perp") { continue }
            guard let quote = Gemini.quotes.first(where: { symbol.hasSuffix($0) && symbol.count > $0.count }) else { continue }
            let base = String(symbol.dropLast(quote.count))
            pairs.append(CurrencyPairInfo(base.uppercased(), quote.uppercased(), symbol))
        }
        return pairs
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        // volume = { "BTC": "...", "USD": "...", "timestamp": ... }
        if let volume = json.optObject("volume") {
            ticker.vol = volume.optDoubleNoData(info.base.uppercased())
            ticker.volQuote = volume.optDoubleNoData(info.quote.uppercased())
            ticker.timestamp = volume.optLong("timestamp")
        }
    }

    // Preisliste aller Paare — nur der letzte Kurs, das genügt für die Watchlist.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.gemini.com/v1/pricefeed" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let pair = item.optString("pair")
            if pair.isEmpty { continue }
            let price = item.optDouble("price", .nan)
            if price.isNaN { continue }
            var ticker = Ticker()
            ticker.last = price
            // Kein 24-h-Wert: Einheit von percentChange24h unklar (Doku «5.23» = Prozent,
            // echte Antworten eher Bruchteil wie «0.0146») — dann lieber Kerzen.
            tickers[pair.lowercased()] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}
