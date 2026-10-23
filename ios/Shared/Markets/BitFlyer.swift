import Foundation

private let bitFlyerAPI = "https://api.bitflyer.com/v1"

/// Produktlisten der Regionen Japan, USA und EU.
private let bitFlyerRegionLists = ["markets", "markets/usa", "markets/eu"]

/// bitFlyer — Spot aus drei Regionen (Japan, USA, EU), je eine Produktliste; darum kein `SimpleMarket`. Der Ticker
/// kennt weder 24-h-Hoch/-Tief noch ein Volumen in der Gegenwährung. https://lightning.bitflyer.com/docs
final class BitFlyer: Market, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() { super.init(key: "BitFlyer", name: "bitFlyer", ttsName: "bit flyer") }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        let code = info.pairId ?? info.base + "_" + info.quote
        return bitFlyerAPI + "/ticker?product_code=" + code
    }

    /// Ruht der Handel («state» nicht «RUNNING»: CLOSED, STARTING, PREOPEN, CIRCUIT BREAK …), gibt es keinen Kurs —
    /// sonst stünde der letzte Handel vor dem Halt als aktuell da (wie Luno). Ohne Feld (Fehlerantwort) zählt das nicht.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        if !json.isNull("state") && json.optString("state") != "RUNNING" {
            throw JSONError(message: JSONError.noData)
        }
        ticker.last = try json.double("ltp")
        ticker.bid = json.optDoubleOrMissing("best_bid")
        ticker.ask = json.optDoubleOrMissing("best_ask")
        ticker.vol = json.optDoubleOrMissing("volume_by_product")
    }

    /// JSON null ergibt hier keinen Text (Android liefert dort «null»).
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.string("error_message")
    }

    // MARK: Paarliste

    override var pairListCallCount: Int { bitFlyerRegionLists.count }

    override func pairListAddress(call: Int) -> String? { bitFlyerAPI + "/" + bitFlyerRegionLists[call] }

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for product in try JArray(string: body).allObjects() {
            guard product.optString("market_type").lowercased() == "spot" else { continue }
            let code = product.optString("product_code")
            // «BTC_JPY» → BTC / JPY; alles andere (Futures-Codes) fällt weg
            let sides = code.split(separator: "_")
            if sides.count == 2 { out.append(MarketPair(String(sides[0]), String(sides[1]), code)) }
        }
        return out
    }
}
