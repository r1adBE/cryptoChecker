import Foundation

/// Independent Reserve (Australien, Neuseeland, Singapur). Es gibt keine
/// Paarliste, nur Coins (Xbt, Eth, …) und Gegenwährungen (Aud, Usd, Nzd, Sgd);
/// die Paare sind alle Kombinationen. Bitcoin heisst dort „Xbt“.
/// https://www.independentreserve.com/features/api
final class IndependentReserve: Market {
    private static let base = "https://api.independentreserve.com/Public"

    init() { super.init(key: "IndependentReserve", name: "Independent Reserve") }

    override var currencyPairsNumOfRequests: Int { 2 }
    override var currencyPairsCombined: Bool { true }

    override func currencyPairsURL(requestId: Int) -> String? {
        requestId == 0 ? "\(IndependentReserve.base)/GetValidPrimaryCurrencyCodes"
                       : "\(IndependentReserve.base)/GetValidSecondaryCurrencyCodes"
    }

    override func parseCurrencyPairsCombined(responses: [String]) throws -> [CurrencyPairInfo] {
        let primaries = try JArray(string: responses[0]).strings.filter { !$0.isEmpty }
        let secondaries = try JArray(string: responses[1]).strings.filter { !$0.isEmpty }
        var pairs: [CurrencyPairInfo] = []
        for primary in primaries {
            for secondary in secondaries where primary.lowercased() != secondary.lowercased() {
                pairs.append(CurrencyPairInfo(IndependentReserve.publicName(primary), IndependentReserve.publicName(secondary),
                                              "\(primary)_\(secondary)"))
            }
        }
        return pairs
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        var parts = (info.pairId ?? "").split(separator: "_").map(String.init)
        if parts.count != 2 {
            parts = [IndependentReserve.exchangeName(info.base), IndependentReserve.exchangeName(info.quote)]
        }
        return "\(IndependentReserve.base)/GetMarketSummary?primaryCurrencyCode=\(parts[0])&secondaryCurrencyCode=\(parts[1])"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        ticker.last = try json.double("LastPrice")
        ticker.bid = json.optDoubleNoData("CurrentHighestBidPrice")
        ticker.ask = json.optDoubleNoData("CurrentLowestOfferPrice")
        ticker.high = json.optDoubleNoData("DayHighestPrice")
        ticker.low = json.optDoubleNoData("DayLowestPrice")
        // Heisst immer „…Xbt“, gilt aber für den jeweiligen Coin
        ticker.vol = json.optDoubleNoData("DayVolumeXbt")
        ticker.timestamp = IndependentReserve.parseTime(json.optString("CreatedTimestampUtc"))
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.string("Message")
    }

    private static func publicName(_ code: String) -> String {
        let upper = code.uppercased()
        return upper == "XBT" ? "BTC" : upper
    }

    private static func exchangeName(_ currency: String) -> String {
        let upper = currency.uppercased()
        if upper == "BTC" { return "Xbt" }
        return String(upper.prefix(1)) + upper.dropFirst().lowercased()
    }

    /// „2022-02-10T09:34:05.2287498Z“ — sieben Nachkommastellen kann
    /// ISO8601DateFormatter nicht immer; auf Millisekunden kürzen.
    private static func parseTime(_ s: String) -> Int64 {
        var text = s
        if let dot = text.firstIndex(of: ".") {
            let fraction = text[text.index(after: dot)...].prefix { $0.isNumber }
            let rest = text[text.index(after: dot)...].dropFirst(fraction.count)
            text = String(text[..<dot]) + "." + String(fraction.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0) + String(rest)
        }
        if !text.hasSuffix("Z") && !text.contains("+") { text += "Z" }
        return TimeUtils.isoToMillis(text)
    }
}
