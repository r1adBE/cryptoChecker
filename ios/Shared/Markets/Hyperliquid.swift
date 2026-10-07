import Foundation

/// Hyperliquid — dezentrale Perpetual-Börse, abgerechnet in USDC.
/// Eine einzige POST-Abfrage liefert Liste und Kurse aller Perps:
/// [ { universe: [ {name, …}, … ] }, [ {markPx, midPx, dayNtlVlm, dayBaseVlm, …}, … ] ]
/// Beide Listen sind über den Index verbunden.
final class Hyperliquid: Market {
    private static let infoURL = "https://api.hyperliquid.xyz/info"

    init() {
        super.init(key: "Hyperliquid", name: "Hyperliquid", ttsName: "Hyperliquid")
    }

    private func request() -> PostRequestInfo {
        PostRequestInfo(
            body: #"{"type":"metaAndAssetCtxs"}"#,
            headers: ["Content-Type": "application/json"]
        )
    }

    override func url(requestId: Int, info: CheckerInfo) -> String { Hyperliquid.infoURL }

    override func postRequestInfo(requestId: Int, info: CheckerInfo) -> PostRequestInfo? { request() }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        let all = try parseAll(response)
        guard let pairId = info.pairId, let found = all[pairId] else {
            throw JSONError(message: "Unknown coin: \(info.pairId ?? "null")")
        }
        ticker.last = found.last
        ticker.vol = found.vol
        ticker.volQuote = found.volQuote
    }

    override func parseError(requestId: Int, response: String, info: CheckerInfo) throws -> String? {
        String(response.prefix(200))
    }

    // ---- Massenabfrage: dieselbe Abfrage
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { Hyperliquid.infoURL }

    override func bulkTickersPostRequestInfo(requestId: Int) -> PostRequestInfo? { request() }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        try parseAll(response)
    }

    override var bulkTickersComplete: Bool { true }

    // ---- Paare
    override func currencyPairsURL(requestId: Int) -> String? { Hyperliquid.infoURL }

    override func currencyPairsPostRequestInfo(requestId: Int) -> PostRequestInfo? { request() }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        let universe = try JArray(string: response).object(0).array("universe")
        var pairs: [CurrencyPairInfo] = []
        for i in 0..<universe.count {
            let coin = try universe.object(i)
            if coin.optBool("isDelisted") { continue }
            let name = try coin.string("name")
            pairs.append(CurrencyPairInfo(name, "USDC", name, .perpetual))
        }
        return pairs
    }

    private func parseAll(_ response: String) throws -> [String: Ticker] {
        let root = try JArray(string: response)
        let universe = try root.object(0).array("universe")
        let contexts = try root.array(1)
        var result: [String: Ticker] = [:]
        for i in 0..<min(universe.count, contexts.count) {
            let coin = try universe.object(i)
            if coin.optBool("isDelisted") { continue }
            guard let ctx = contexts.optObject(i) else { continue }
            // midPx fehlt bei dünnem Orderbuch, dann der Mark-Preis.
            let mid = ctx.optDouble("midPx", .nan)
            let price = mid.isNaN ? ctx.optDouble("markPx", .nan) : mid
            if price.isNaN { continue }
            var ticker = Ticker()
            ticker.last = price
            ticker.vol = ctx.optDoubleNoData("dayBaseVlm")
            ticker.volQuote = ctx.optDoubleNoData("dayNtlVlm")
            let name = try coin.string("name")
            result[name] = ticker
        }
        return result
    }
}
