import Foundation

// API Reference: https://docs.cloud.coinbase.com/exchange/reference/
final class Coinbase: Market {
    private static let urlProductsBase = "https://api.exchange.coinbase.com/products/"   // + id + "/stats"
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

    /// Eine Anfrage je Paar: /stats liefert gleitend über 24 h open, high, low, last und
    /// volume — alles, was Merkliste, Alarme und Widgets brauchen. /ticker kam nur für
    /// Bid/Ask und den Zeitstempel dazu; Bid/Ask zeigt die App nirgends dauerhaft an (nur
    /// die Vorschau beim Hinzufügen, die fehlende Werte ausblendet). Wie `Coinbase.kt`.
    override func numOfRequests(_ info: CheckerInfo) -> Int { 1 }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        let pairId = info.pairId ?? "\(info.base)-\(info.quote)"
        return "\(Coinbase.urlProductsBase)\(pairId)/stats"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        // Unbekannte oder stillgelegte Produkte liefern statt der Kursdaten
        // ein Objekt mit "message" — sonst nur "No value for volume".
        let message = json.optString("message")
        if !message.isEmpty { throw JSONError(message: message) }

        let volume = try json.double("volume")
        if volume <= 0 { throw JSONError(message: "No trading volume") }
        ticker.vol = volume
        ticker.last = try json.double("last")
        ticker.high = try json.double("high")
        ticker.low = try json.double("low")
        // „open“ = Kurs vor 24 h (gleitend)
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("open"))
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
