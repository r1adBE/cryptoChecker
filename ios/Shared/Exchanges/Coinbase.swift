import Foundation

private let coinbaseAPI = "https://api.exchange.coinbase.com"

/// Paare vor der ersten Synchronisierung (Basis → Gegenwährungen).
private let coinbaseStarterPairs: CurrencyPairsMap = [
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

/// Coinbase Exchange (https://docs.cloud.coinbase.com/exchange/reference/). Kennung = Produkt-ID («BTC-USD»).
///
/// Eine Anfrage je Paar: `/stats` liefert gleitend über 24 h open, high, low, last und volume — genug für Merkliste,
/// Alarme und Widgets. Geld/Brief aus `/ticker` zeigt die App nirgends dauerhaft (die Vorschau beim Hinzufügen
/// blendet fehlende Werte aus); die Zeit ist dann der Abruf.
final class Coinbase: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Coinbase",
            name: "Coinbase",
            pairListURL: coinbaseAPI + "/products",
            tickerURL: coinbaseAPI + "/products/%1$s/stats",
            errorField: "message",
            currencyPairs: coinbaseStarterPairs
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        // Unbekanntes oder stillgelegtes Produkt: {"message": …} statt Kursdaten — so melden statt «volume fehlt»
        let notice = json.optString("message")
        if !notice.isEmpty { throw JSONError(message: notice) }

        let last = try json.double("last")
        ticker.last = last
        ticker.high = try json.double("high")
        ticker.low = try json.double("low")
        // Volumen 0 ist ein ruhiges Paar (gültig); nur negative oder unlesbare Werte bleiben «keine Daten»
        let volume = json.optDouble("volume")
        if volume.isFinite && volume >= 0 { ticker.vol = volume }
        // «open» = Kurs vor 24 h, gleitend
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: json.optDouble("open"))
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for product in try JArray(string: body).allObjects() where Coinbase.isTradable(product) {
            out.append(ExchangePair(
                try product.string("base_currency"),
                try product.string("quote_currency"),
                try product.string("id")
            ))
        }
        return out
    }

    /// Nur handelbare Produkte: Status «online» und Handel nicht gesperrt. «delisted», «offline», «internal» oder
    /// `trading_disabled` liefern keinen frischen Kurs — sie in der Auswahl anzubieten, hiesse einen stillstehenden Preis
    /// zu beobachten. Fehlt der Status ganz, bleibt das Produkt (Format geändert: lieber anbieten als die ganze Liste
    /// verlieren). Wie `Coinbase.isTradable` (Android).
    private static func isTradable(_ product: JObject) -> Bool {
        let status = product.optString("status")
        if !status.isEmpty && status.lowercased() != "online" { return false }
        return !product.optBool("trading_disabled")
    }
}
