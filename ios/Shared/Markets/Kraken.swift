import Foundation

// Ref: https://docs.kraken.com/rest/#tag/Market-Data
final class Kraken: SimpleMarket {
    private static let allTickersURL = "https://api.kraken.com/0/public/Ticker"
    private static let filteredTickersPrefix = Kraken.allTickersURL + "?pair="

    init() {
        super.init(
            key: "Kraken",
            name: "Kraken",
            pairsURL: "https://api.kraken.com/0/public/AssetPairs",
            tickerURL: "https://api.kraken.com/0/public/Ticker?pair=%1$s"
        )
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for (pairId, pairJson) in try json.object("result").allNamedObjects() {
            if !pairId.contains(".") {
                try pairs.append(CurrencyPairInfo(
                    Kraken.parseCurrency(pairJson.string("base")),
                    Kraken.parseCurrency(pairJson.string("quote")),
                    pairId
                ))
            }
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? {
        super.pairId(info) ?? (Kraken.fixCurrency(info.base) + Kraken.fixCurrency(info.quote))
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let result = try json.object("result")
        // Einziger Eintrag; bei Swift-Dictionaries ist "erster" ohnehin der einzige.
        guard let firstName = result.keys.first else { throw JSONError(message: "Empty result") }
        try readTicker(result.object(firstName), &ticker)
    }

    /// Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur.
    private func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        // a/b/c: [Preis, …] – Index 0 ist der Kurs
        ticker.bid = try Kraken.doubleFromArray(json, "b", 0)
        ticker.ask = try Kraken.doubleFromArray(json, "a", 0)

        // h/l/v: [heute seit 00:00 UTC, gleitende 24 h] – die App zeigt 24-h-Werte, also Index 1
        ticker.high = try Kraken.doubleFromArray(json, "h", 1)
        ticker.low = try Kraken.doubleFromArray(json, "l", 1)

        ticker.vol = try Kraken.doubleFromArray(json, "v", 1)
        ticker.last = try Kraken.doubleFromArray(json, "c", 0)
        // Kein 24-h-Wert: „o“ ist die Eröffnung des UTC-Tages, nicht der Kurs vor 24 h.
    }

    /// Ohne pair-Parameter liefert der Endpunkt alle handelbaren Paare.
    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { Kraken.allTickersURL }

    /// Nur die beobachteten Paare («?pair=A,B,C») statt des ganzen Kursbuchs;
    /// lange Listen auf mehrere Anfragen verteilt (URL < 2000 Zeichen). Wie `Kraken.kt`.
    override func bulkTickersRequestCount(pairIds: [String]) -> Int {
        BulkPairChunks.chunks(prefix: Kraken.filteredTickersPrefix, pairIds: pairIds)?.count ?? bulkTickersNumOfRequests
    }

    override func bulkTickersURL(requestId: Int, pairIds: [String]) -> String? {
        guard let chunks = BulkPairChunks.chunks(prefix: Kraken.filteredTickersPrefix, pairIds: pairIds) else {
            return Kraken.allTickersURL
        }
        guard requestId < chunks.count else { return nil }
        return BulkPairChunks.url(prefix: Kraken.filteredTickersPrefix, chunk: chunks[requestId])
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        let json = try JObject(string: response)
        // Kennt Kraken ein Paar der Liste nicht, scheitert die ganze Anfrage
        // ({"error":["EQuery:Unknown asset pair"]}) — dann folgt die ungefilterte.
        let named = try json.optObject("result")?.allNamedObjects() ?? []
        if named.isEmpty {
            let error = json.optArray("error")?.optString(0) ?? ""
            throw JSONError(message: error.isEmpty ? "Empty result" : error)
        }
        var tickers: [String: Ticker] = [:]
        for (pairId, pairJson) in named {
            var ticker = Ticker()
            try readTicker(pairJson, &ticker)
            // Ohne letzten Kurs (leeres «c») kein Eintrag – der Einzelabruf meldet dann den Fehler
            if ticker.last <= Ticker.noData { continue }
            tickers[pairId] = ticker
        }
        return tickers
    }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        try json.array("error").string(0)
    }

    private static func fixCurrency(_ currency: String) -> String {
        if currency == VirtualCurrency.BTC { return VirtualCurrency.XBT }
        if currency == VirtualCurrency.VEN { return VirtualCurrency.XVN }
        return currency == VirtualCurrency.DOGE ? VirtualCurrency.XDG : currency
    }

    /// Wert an `index` der Liste `arrayKey`; fehlt die Liste oder ist sie zu kurz: kein Wert (nicht 0).
    private static func doubleFromArray(_ json: JObject, _ arrayKey: String, _ index: Int) throws -> Double {
        guard let array = json.optArray(arrayKey), array.count > index else { return Ticker.noData }
        return try array.double(index)
    }

    private static func parseCurrency(_ currency: String) -> String {
        KrakenAssetCodes.normalize(currency)
    }
}

/// Normalisiert Kraken-Asset-Codes aus /0/public/AssetPairs (Spiegel von KrakenAssetCodes.kt).
///
/// Nur Krakens bekannte 4-stellige Legacy-Codes (X + Krypto, Z + Fiat, z. B. XXBT, ZUSD)
/// werden gekürzt; alle übrigen Codes (XTZ, ZRX, ZETA, XCN, ZK, ZEC …) bleiben unverändert.
/// Danach werden XBT/XDG/XVN auf BTC/DOGE/VEN abgebildet.
enum KrakenAssetCodes {
    private static let legacyX: Set<String> = [
        "XBT", "ETH", "LTC", "XRP", "XLM", "XMR", "ZEC", "REP", "ETC", "MLN", "XDG", "NMC", "XVN", "ICN",
    ]
    private static let legacyZ: Set<String> = [
        "USD", "EUR", "GBP", "CAD", "JPY", "CHF", "AUD", "KRW",
    ]
    private static let aliases: [String: String] = [
        "XBT": "BTC",
        "XDG": "DOGE",
        "XVN": "VEN",
    ]

    static func normalize(_ code: String) -> String {
        var stripped = code
        if code.count == 4, let first = code.first {
            let rest = String(code.dropFirst())
            if first == "X" && legacyX.contains(rest) { stripped = rest }
            if first == "Z" && legacyZ.contains(rest) { stripped = rest }
        }
        return aliases[stripped] ?? stripped
    }
}
