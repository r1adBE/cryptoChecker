import Foundation

// API Reference: https://docs.cloud.coinbase.com/exchange/reference/
final class Coinbase: Market {
    private static let urlTickerBase = "https://api.exchange.coinbase.com/products/"   // + id + "/ticker"
    private static let urlCurrencyPairs = "https://api.exchange.coinbase.com/products"

    private static let currencyPairsMap: [String: [String]] = [
        "1INCH": ["BTC", "EUR", "GBP", "USD"],
        "AAVE": ["BTC", "EUR", "GBP", "USD"],
        "ADA": ["BTC", "ETH", "EUR", "GBP", "USD", "USDC"],
        "ATOM": ["BTC", "USD"],
        "BAT": ["BTC", "ETH", "EUR", "USD", "USDC"],
        "BTC": ["EUR", "GBP", "USDC", "USD", "USDT"],
        "DAI": ["USD", "USDC"],
        "DASH": ["BTC", "USD"],
        "DOGE": ["BTC", "EUR", "GBP", "USD", "USDT"],
        "DOT": ["BTC", "EUR", "GBP", "USD", "USDT"],
        "EOS": ["BTC", "EUR", "USD"],
        "ETC": ["BTC", "EUR", "GBP", "USD"],
        "ETH": ["BTC", "DAI", "EUR", "GBP", "USD", "USDT", "USDC"],
        "FIL": ["BTC", "EUR", "GBP", "USD"],
        "LINK": ["BTC", "ETH", "EUR", "GBP", "USD"],
        "LTC": ["BTC", "EUR", "GBP", "USD"],
        "OMG": ["BTC", "EUR", "GBP", "USD"],
        "STORJ": ["BTC", "USD"],
        "SUSHI": ["BTC", "ETH", "EUR", "GBP", "USD"],
        "USDC": ["EUR", "GBP"],
        "USDT": ["EUR", "GBP", "USD", "USDC"],
        "XLM": ["BTC", "EUR", "USD"],
        "ZEC": ["BTC", "USD", "USDC"],
    ]

    init() {
        super.init(key: "Coinbase", name: "Coinbase", ttsName: "Coinbase", currencyPairs: Coinbase.currencyPairsMap)
    }

    override func numOfRequests(_ info: CheckerInfo) -> Int { 2 }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        let pairId = info.pairId ?? "\(info.base)-\(info.quote)"

        if requestId == 0 {
            return "\(Coinbase.urlTickerBase)\(pairId)/ticker"
        }
        return "\(Coinbase.urlTickerBase)\(pairId)/stats"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        // Unbekannte oder stillgelegte Produkte liefern statt der Kursdaten
        // ein Objekt mit "message" — sonst nur "No value for volume".
        let message = json.optString("message")
        if !message.isEmpty { throw JSONError(message: message) }

        if requestId == 0 {
            let volume = try json.double("volume")
            if volume <= 0 { throw JSONError(message: "No trading volume") }
            ticker.vol = volume

            ticker.bid = try json.double("bid")
            ticker.ask = try json.double("ask")
            ticker.last = try json.double("price")
            ticker.timestamp = try Coinbase.isoToMillis(json.string("time"))
        } else {
            ticker.high = try json.double("high")
            ticker.low = try json.double("low")
        }
    }

    /// Wie `TimeUtils.convertISODateToTimestamp`: ganze Sekunden × 1000.
    /// Coinbase liefert Mikrosekunden ("…:56.123456Z"); die Nachkommastellen
    /// werden vorher entfernt, weil ISO8601DateFormatter nicht jede Länge liest.
    /// Kotlin würde bei unlesbarem Datum werfen; hier 0 → Zeitpunkt "jetzt".
    private static func isoToMillis(_ s: String) -> Int64 {
        let withoutFraction = s.replacingOccurrences(of: #"\.[0-9]+"#, with: "", options: .regularExpression)
        return TimeUtils.isoToMillis(withoutFraction)
    }

    override func currencyPairsURL(requestId: Int) -> String? { Coinbase.urlCurrencyPairs }

    override func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for pair in try JArray(string: response).allObjects() {
            if pair.optString("status") != "delisted" {
                try pairs.append(CurrencyPairInfo(
                    pair.string("base_currency"),
                    pair.string("quote_currency"),
                    pair.string("id")
                ))
            }
        }
        return pairs
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.string("message")
    }
}
