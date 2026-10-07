import Foundation

/// bitFlyer (Japan, USA, EU) — nur Spot. Der Ticker hat weder 24-h-Hoch/-Tief
/// noch ein Gegenwert-Volumen. https://lightning.bitflyer.com/docs
final class BitFlyer: Market {
    private static let base = "https://api.bitflyer.com/v1"
    private static let marketLists = ["markets", "markets/usa", "markets/eu"]

    init() { super.init(key: "BitFlyer", name: "bitFlyer", ttsName: "bit flyer") }

    override var currencyPairsNumOfRequests: Int { BitFlyer.marketLists.count }

    override func currencyPairsURL(requestId: Int) -> String? { "\(BitFlyer.base)/\(BitFlyer.marketLists[requestId])" }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try JArray(string: response).allObjects() {
            if item.optString("market_type").lowercased() != "spot" { continue }
            let code = item.optString("product_code")
            let parts = code.split(separator: "_").map(String.init)
            if parts.count != 2 { continue }
            pairs.append(CurrencyPairInfo(parts[0], parts[1], code))
        }
        return pairs
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "\(BitFlyer.base)/ticker?product_code=\(info.pairId ?? "\(info.base)_\(info.quote)")"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        ticker.last = try json.double("ltp")
        ticker.bid = json.optDoubleNoData("best_bid")
        ticker.ask = json.optDoubleNoData("best_ask")
        ticker.vol = json.optDoubleNoData("volume_by_product")
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.string("error_message")
    }
}
