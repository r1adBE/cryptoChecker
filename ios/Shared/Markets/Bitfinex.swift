import Foundation

/// Bitfinex Spot. Antworten sind Arrays statt Objekte:
/// Ticker = [BID, BID_SIZE, ASK, ASK_SIZE, DAILY_CHANGE, DAILY_CHANGE_REL, LAST, VOLUME, HIGH, LOW]
/// Bitfinex nennt USDT intern „UST“; angezeigt wird USDT.
final class Bitfinex: Market {

    init() {
        super.init(key: "Bitfinex", name: "Bitfinex", ttsName: "Bitfinex")
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "https://api-pub.bitfinex.com/v2/ticker/\(info.pairId ?? "null")"
    }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        let array = try JArray(string: response)
        // Fehler kommen als ["error", code, "meldung"]
        if array.optString(0) == "error" { throw JSONError(message: array.optString(2)) }
        try read(array, 0, &ticker)
    }

    override func parseError(requestId: Int, response: String, info: CheckerInfo) throws -> String? {
        let message = try JArray(string: response).optString(2)
        return message.isEmpty ? nil : message
    }

    private func read(_ array: JArray, _ offset: Int, _ ticker: inout Ticker) throws {
        ticker.bid = try array.double(offset + 0)
        ticker.ask = try array.double(offset + 2)
        ticker.last = try array.double(offset + 6)
        ticker.vol = try array.double(offset + 7)
        ticker.high = try array.double(offset + 8)
        ticker.low = try array.double(offset + 9)
    }

    // ---- Massenabfrage
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api-pub.bitfinex.com/v2/tickers?symbols=ALL" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for item in try JArray(string: response).allArrays() {
            let symbol = item.optString(0)
            if !symbol.hasPrefix("t") { continue }   // „f…“ = Funding, keine Handelspaare
            var ticker = Ticker()
            do { try read(item, 1, &ticker) } catch { continue }
            tickers[symbol] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }

    // ---- Handelspaare
    override func currencyPairsURL(requestId: Int) -> String? {
        "https://api-pub.bitfinex.com/v2/conf/pub:list:pair:exchange"
    }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        let list = try JArray(string: response).array(0)
        var pairs: [CurrencyPairInfo] = []
        for i in 0..<list.count {
            let pair = try list.string(i)
            if pair.hasPrefix("TEST") { continue }
            let base: String
            let quote: String
            if let colon = pair.firstIndex(of: ":") {
                base = String(pair[..<colon])
                quote = String(pair[pair.index(after: colon)...])
            } else if pair.count == 6 {
                base = String(pair.prefix(3))
                quote = String(pair.dropFirst(3))
            } else {
                continue
            }
            pairs.append(CurrencyPairInfo(displayName(base), displayName(quote), "t\(pair)"))
        }
        return pairs
    }

    private func displayName(_ asset: String) -> String { asset == "UST" ? "USDT" : asset }
}
