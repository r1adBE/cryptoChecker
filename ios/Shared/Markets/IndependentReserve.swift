import Foundation

private let independentReserveAPI = "https://api.independentreserve.com/Public"

/// Independent Reserve (Australien, Neuseeland, Singapur). Statt einer Paarliste gibt es zwei Listen — Coins (Xbt,
/// Eth, …) und Gegenwährungen (Aud, Usd, Nzd, Sgd); jedes Paar daraus ist handelbar (`pairListCombined`, darum kein
/// `SimpleMarket`). Bitcoin heisst dort «Xbt». https://www.independentreserve.com/features/api
final class IndependentReserve: Market {
    init() { super.init(key: "IndependentReserve", name: "Independent Reserve") }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        let stored = (info.pairId ?? "").split(separator: "_").map(String.init)
        let codes = stored.count == 2
            ? stored
            : [IndependentReserve.apiCode(info.base), IndependentReserve.apiCode(info.quote)]
        return independentReserveAPI
            + "/GetMarketSummary?primaryCurrencyCode=\(codes[0])&secondaryCurrencyCode=\(codes[1])"
    }

    /// `CreatedTimestampUtc` ist die Erstellung der Übersicht, kein Handel: Zeit der Börse unbekannt.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        ticker.last = try json.double("LastPrice")
        ticker.bid = json.optDoubleOrMissing("CurrentHighestBidPrice")
        ticker.ask = json.optDoubleOrMissing("CurrentLowestOfferPrice")
        ticker.high = json.optDoubleOrMissing("DayHighestPrice")
        ticker.low = json.optDoubleOrMissing("DayLowestPrice")
        // Heisst immer «…Xbt», gilt aber für den jeweiligen Coin. «DayVolumeXbt» zählt alle Gegenwährungen zusammen,
        // «…InSecondaryCurrrency» (so geschrieben, drei r) nur dieses Paar — beides in Coins, nicht in der Gegenwährung
        ticker.vol = json.optDoubleOrMissing("DayVolumeXbtInSecondaryCurrrency")
    }

    /// JSON null ergibt hier keinen Text (Android liefert dort «null»).
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.string("Message")
    }

    // MARK: Paarliste: Coins (0) mal Gegenwährungen (1)

    override var pairListCallCount: Int { 2 }

    override var pairListCombined: Bool { true }

    override func pairListAddress(call: Int) -> String? {
        independentReserveAPI + (call == 0 ? "/GetValidPrimaryCurrencyCodes" : "/GetValidSecondaryCurrencyCodes")
    }

    override func fillPairListCombined(bodies: [String]) throws -> [MarketPair] {
        let coins = try IndependentReserve.codeList(bodies[0])
        let counters = try IndependentReserve.codeList(bodies[1])
        var out: [MarketPair] = []
        for coin in coins {
            for counter in counters where counter.lowercased() != coin.lowercased() {
                out.append(MarketPair(IndependentReserve.displayCode(coin), IndependentReserve.displayCode(counter),
                                            coin + "_" + counter))
            }
        }
        return out
    }

    // MARK: Lesen

    /// Nicht leere Codes einer Liste; Einträge, die JSON null sind, fallen weg.
    private static func codeList(_ body: String) throws -> [String] {
        try JArray(string: body).strings.filter { !$0.isEmpty }
    }

    /// Code der Börse → Anzeige («Xbt» → «BTC», sonst gross).
    private static func displayCode(_ code: String) -> String {
        let upper = code.uppercased()
        return upper == "XBT" ? "BTC" : upper
    }

    /// Anzeige → Code der Börse («BTC» → «Xbt», sonst «Eth»-Schreibweise).
    private static func apiCode(_ currency: String) -> String {
        let upper = currency.uppercased()
        if upper == "BTC" { return "Xbt" }
        return String(upper.prefix(1)) + upper.dropFirst().lowercased()
    }
}
