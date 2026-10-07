import Foundation

/// Basis aller Börsen — entspricht `Market.kt` der Android-Fassung.
/// Unterklassen überschreiben nur, was sie brauchen.
///
/// Börsen halten keinen veränderlichen Zustand und dürfen von mehreren
/// Aufgaben gleichzeitig benutzt werden.
class Market: @unchecked Sendable {
    /// Schlüssel wie in Android (Kotlin-Klassenname) — Sicherungen bleiben austauschbar.
    let key: String
    let name: String
    let ttsName: String
    /// Fest hinterlegte Paare (nur wenige Börsen), Basis → Gegenwerte.
    let currencyPairs: [String: [String]]?

    init(key: String, name: String, ttsName: String? = nil, currencyPairs: [String: [String]]? = nil) {
        self.key = key
        self.name = name
        self.ttsName = ttsName ?? name
        self.currencyPairs = currencyPairs
    }

    // MARK: Einzelkurs

    func numOfRequests(_ info: CheckerInfo) -> Int { 1 }

    func url(requestId: Int, info: CheckerInfo) -> String {
        fatalError("url(requestId:info:) muss überschrieben werden (\(key))")
    }

    /// Body gesetzt → POST, sonst GET.
    func postRequestInfo(requestId: Int, info: CheckerInfo) -> PostRequestInfo? { nil }

    final func parseTickerMain(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        try parseTicker(requestId: requestId, response: response, ticker: &ticker, info: info)
        if ticker.timestamp <= 0 {
            ticker.timestamp = TimeUtils.nowMillis
        } else {
            ticker.timestamp = TimeUtils.parseTimeToMillis(ticker.timestamp)
        }
    }

    func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        try parseTicker(requestId: requestId, json: JObject(string: response), ticker: &ticker, info: info)
    }

    func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {}

    // MARK: Fehlertext

    final func parseErrorMain(requestId: Int, response: String, info: CheckerInfo) throws -> String? {
        try parseError(requestId: requestId, response: response, info: info)
    }

    func parseError(requestId: Int, response: String, info: CheckerInfo) throws -> String? {
        try parseError(requestId: requestId, json: JObject(string: response), info: info)
    }

    func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        throw JSONError(message: "Kein Fehlertext")
    }

    // MARK: Sammelabfrage (optional)

    /// 0 = nicht unterstützt.
    var bulkTickersNumOfRequests: Int { 0 }

    func bulkTickersURL(requestId: Int) -> String? { nil }

    /// Wie `bulkTickersURL(requestId:)`, aber nur für diese Paar-Kennungen.
    func bulkTickersURL(requestId: Int, pairIds: [String]) -> String? { bulkTickersURL(requestId: requestId) }

    /// true: Die ungefilterte Sammelabfrage enthält alle gehandelten Paare.
    var bulkTickersComplete: Bool { false }

    func bulkTickersPostRequestInfo(requestId: Int) -> PostRequestInfo? { nil }

    final func parseBulkTickersMain(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers = try parseBulkTickers(requestId: requestId, response: response)
        for (k, var t) in tickers {
            t.timestamp = t.timestamp <= 0 ? TimeUtils.nowMillis : TimeUtils.parseTimeToMillis(t.timestamp)
            tickers[k] = t
        }
        return tickers
    }

    /// Schlüssel = Paar-Kennung (`CurrencyPairInfo.pairId`).
    func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] { [:] }

    // MARK: Paarliste

    var currencyPairsNumOfRequests: Int { 1 }

    func currencyPairsURL(requestId: Int) -> String? { nil }

    func currencyPairsPostRequestInfo(requestId: Int) -> PostRequestInfo? { nil }

    final func parseCurrencyPairsMain(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        try parseCurrencyPairs(requestId: requestId, response: response)
            .filter { !$0.base.isEmpty && !$0.quote.isEmpty }
    }

    /// true: Antworten aller Paar-Anfragen erst sammeln, dann gemeinsam in
    /// `parseCurrencyPairsCombined` auswerten (z. B. LATOKEN: Währungs-IDs → Kürzel).
    /// Scheitert eine Anfrage, scheitert die ganze Synchronisierung.
    var currencyPairsCombined: Bool { false }

    final func parseCurrencyPairsCombinedMain(responses: [String]) throws -> [CurrencyPairInfo] {
        try parseCurrencyPairsCombined(responses: responses)
            .filter { !$0.base.isEmpty && !$0.quote.isEmpty }
    }

    func parseCurrencyPairsCombined(responses: [String]) throws -> [CurrencyPairInfo] { [] }

    func parseCurrencyPairs(requestId: Int, response: String) throws -> [CurrencyPairInfo] {
        try parseCurrencyPairs(requestId: requestId, json: JObject(string: response))
    }

    func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] { [] }
}

/// Börse mit fester Paar- und Kurs-URL — entspricht `SimpleMarket.kt`.
/// `tickerURL` enthält `%1$@` (bzw. `%1$s`) als Platzhalter für die Paar-Kennung.
class SimpleMarket: Market {
    let pairsURL: String
    let tickerURL: String
    let errorPropertyName: String?

    init(key: String, name: String, pairsURL: String, tickerURL: String, ttsName: String? = nil, errorPropertyName: String? = nil) {
        self.pairsURL = pairsURL
        self.tickerURL = tickerURL
        self.errorPropertyName = errorPropertyName
        super.init(key: key, name: name, ttsName: ttsName)
    }

    override func currencyPairsURL(requestId: Int) -> String? { pairsURL }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        tickerURL
            .replacingOccurrences(of: "%1$s", with: pairId(info) ?? "")
            .replacingOccurrences(of: "%1$@", with: pairId(info) ?? "")
    }

    func pairId(_ info: CheckerInfo) -> String? { info.pairId }

    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        if let errorPropertyName { return try json.string(errorPropertyName) }
        return try super.parseError(requestId: requestId, json: json, info: info)
    }
}

extension String {
    /// URL-Kodierung für Abfrageparameter (wie `URLEncoder.encode(…, "UTF-8")`).
    var urlQueryEncoded: String {
        // Nur ASCII: `CharacterSet.alphanumerics` enthält auch Umlaute, CJK usw.,
        // die dann unkodiert in der URL landen würden (URLEncoder kodiert sie).
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}
