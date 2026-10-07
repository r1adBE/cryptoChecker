import Foundation

/// LATOKEN. API v2: Paare und Ticker nennen die Währungen nur per ID (UUID);
/// die Kürzel kommen aus der Währungsliste. Paar-Kennung „<Basis-ID>/<Quote-ID>“.
/// Achtung: „amount24h“ ist das Volumen im Coin, „volume24h“ das im Gegenwert.
final class Latoken: SimpleMarket {
    init() {
        super.init(
            key: "Latoken",
            name: "LATOKEN",
            pairsURL: "",
            tickerURL: "https://api.latoken.com/v2/ticker/%1$s",
            ttsName: "La token",
            errorPropertyName: "message"
        )
    }

    override var currencyPairsNumOfRequests: Int { 2 }
    override var currencyPairsCombined: Bool { true }

    override func currencyPairsURL(requestId: Int) -> String? {
        requestId == 0 ? "https://api.latoken.com/v2/currency" : "https://api.latoken.com/v2/pair"
    }

    override func parseCurrencyPairsCombined(responses: [String]) throws -> [CurrencyPairInfo] {
        var tags: [String: String] = [:]
        for currency in try JArray(string: responses[0]).allObjects() {
            let id = currency.optString("id")
            let tag = currency.optString("tag")
            if !id.isEmpty && !tag.isEmpty { tags[id] = tag }
        }
        var pairs: [CurrencyPairInfo] = []
        for pair in try JArray(string: responses[1]).allObjects() {
            if pair.optString("status") != "PAIR_STATUS_ACTIVE" { continue }
            let baseId = pair.optString("baseCurrency")
            let quoteId = pair.optString("quoteCurrency")
            guard let base = tags[baseId], let quote = tags[quoteId] else { continue }
            pairs.append(CurrencyPairInfo(base, quote, "\(baseId)/\(quoteId)"))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)/\(info.quote)" }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json, &ticker)
    }

    private func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("lastPrice")
        ticker.bid = json.optDoubleNoData("bestBid")
        ticker.ask = json.optDoubleNoData("bestAsk")
        ticker.vol = json.optDoubleNoData("amount24h")
        ticker.volQuote = json.optDoubleNoData("volume24h")
        ticker.timestamp = json.optLong("updateTimestamp")
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.latoken.com/v2/ticker" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allObjects() {
            let baseId = item.optString("baseCurrency")
            let quoteId = item.optString("quoteCurrency")
            if baseId.isEmpty || quoteId.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers["\(baseId)/\(quoteId)"] = ticker
        }
        return tickers
    }
}
