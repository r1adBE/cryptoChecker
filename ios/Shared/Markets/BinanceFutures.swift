import Foundation

private let binanceFapi = "https://fapi.binance.com/fapi/v1"
private let binanceDapi = "https://dapi.binance.com/dapi/v1"

/// 24-h-Ticker je Endpunkt: mit `symbol` ein Kontrakt, ohne Parameter alle.
private let binanceFapiDayTicker = binanceFapi + "/ticker/24hr"
private let binanceDapiDayTicker = binanceDapi + "/ticker/24hr"

/// Vorsatz der COIN-M-Kennungen.
private let binanceCoinMMark = "2:"

/// Binance Futures: USDⓈ-M (fapi, Kennung = Symbol) und COIN-M (dapi, Kennung «2:» + Symbol). Zwei Endpunkte, die
/// ohne `symbol` je alle Kontrakte liefern — darum kein `SimpleMarket`.
final class BinanceFutures: Market, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    /// Abschnitt «Futures» der Börsenauswahl (`MarketCategory`). Android gleich.
    override var category: MarketCategory { .futures }

    init() {
        super.init(key: "BinanceFutures", name: "Binance Futures", ttsName: "Binance Futures")
    }

    /// Darf der Kontrakt gewählt werden? Nur «TRADING» — abgelaufene, abrechnende oder geschlossene (DELIVERING,
    /// SETTLING, CLOSE …) nicht. USD-M nennt das Feld «status», COIN-M «contractStatus»; fehlt beides, gilt er als
    /// wählbar.
    static func isTradingContract(_ market: JObject) -> Bool {
        var state = market.optString("status")
        if state.isEmpty { state = market.optString("contractStatus") }
        return state.isEmpty || state == "TRADING"
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        let pairId = info.pairId ?? ""
        let endpoint = BinanceFutures.dayTicker(of: pairId)
        let symbol = BinanceFutures.isCoinM(pairId) ? String(pairId.dropFirst(binanceCoinMMark.count)) : pairId
        // Laufzeit-Kontrakt: immer das Symbol der Paarliste («BTCUSDT_251226»). Der Kalender träfe nach der
        // Abrechnung (Freitag 08:00 UTC) den abgelaufenen Kontrakt — er dient nur alten Kennungen ohne Datum.
        guard FuturesContractType.deliveryCode(of: symbol) == nil, let delivery = info.contractType.deliveryDate else {
            return endpoint + "?symbol=" + symbol
        }
        return endpoint + "?symbol=" + info.base + info.quote + "_" + BinanceFutures.expiryCode(delivery)
    }

    /// COIN-M antwortet mit einem Array aus einem Eintrag.
    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let row = try BinanceFutures.isCoinM(info.pairId ?? "") ? JArray(string: body).object(0) : JObject(string: body)
        try BinanceFutures.copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 2 }

    /// Ungefiltert enthält jede Antwort alle gehandelten Kontrakte.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? {
        call == 0 ? binanceFapiDayTicker : binanceDapiDayTicker
    }

    /// USDⓈ-M (0) und COIN-M (1) je für sich «vollständig»: fällt dapi aus, bleibt fapi verlässlich.
    override func bulkTickersRequestGroup(call: Int) -> Int { call == 0 ? 0 : 1 }

    override func bulkTickersPairGroup(pairId: String) -> Int { BinanceFutures.isCoinM(pairId) ? 1 : 0 }

    /// Einträge, die keine Objekte sind, übergehen; unlesbare Zahlen treffen nur ihr Paar.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let coinM = call > 0
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).optObjects() {
            let symbol = row.optString("symbol")
            let pairId = coinM && !symbol.isEmpty ? binanceCoinMMark + symbol : symbol
            bulkRowIfActive(pairId, row: row, priceField: "lastPrice", tickers: &tickers, failed: &failed) {
                try BinanceFutures.copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    override var pairListCallCount: Int { 2 }

    /// Ohne COIN-M (dapi) keine halbe Liste speichern: dann bleibt die bisherige.
    override var pairListAllCallsRequired: Bool { true }

    override func pairListAddress(call: Int) -> String? {
        call == 0 ? binanceFapi + "/exchangeInfo" : binanceDapi + "/exchangeInfo"
    }

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        let coinM = call > 0
        var out: [MarketPair] = []
        for entry in try json.array("symbols").allObjects() {
            let kind = try entry.string("contractType")
            guard let type = BinanceFutures.contractKind(kind, coinM: coinM),
                  BinanceFutures.isTradingContract(entry) else { continue }
            let symbol = try entry.string("symbol")
            out.append(MarketPair(
                try entry.string("baseAsset"),
                try entry.string("quoteAsset"),
                coinM ? binanceCoinMMark + symbol : symbol,
                type,
                // Immer gesetzt (auch false): daran erkennt der Paarspeicher Listen älterer Versionen
                tradFi: TradFi.binance(contractType: kind, subTypes: entry.optStrings("underlyingSubType"))
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Ein 24-h-Eintrag in `ticker`. Unbekanntes Symbol: Binance schickt {"code":…,"msg":…} — als Fehlertext melden
    /// statt «volume fehlt». `closeTime` ist nur das Fensterende, kein Handel: die Zeit der Börse bleibt unbekannt.
    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let problem = row.optString("msg")
        if !problem.isEmpty { throw JSONError(message: problem) }

        ticker.last = try row.double("lastPrice")
        ticker.high = try row.double("highPrice")
        ticker.low = try row.double("lowPrice")
        // COIN-M: «volume» zählt Kontrakte, «baseVolume» ist die Basismenge; USD-M kennt nur «volume» (Basis)
        ticker.vol = try row.double(row.isNull("baseVolume") ? "volume" : "baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // gleitende 24 h, bereits Prozent
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }

    /// Vertragsart der Börse → App. TradFi-Perpetuals (Aktien, Rohstoffe, Devisen, Pre-IPO) laufen technisch wie
    /// gewöhnliche Perpetuals. COIN-M rechnet in Coins ab: dort ist ein Perpetual invers. Unbekannt → nil.
    private static func contractKind(_ kind: String, coinM: Bool) -> FuturesContractType? {
        switch kind {
        case "PERPETUAL", TradFi.binanceTradFiPerpetual: coinM ? FuturesContractType.inversePerpetual : .perpetual
        case "CURRENT_QUARTER": .quarterly
        case "NEXT_QUARTER": .biquarterly
        default: nil
        }
    }

    /// Verfallstag im Symbol der Laufzeit-Kontrakte («251226», UTC).
    private static func expiryCode(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        func twoDigits(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
        return twoDigits((parts.year ?? 0) % 100) + twoDigits(parts.month ?? 0) + twoDigits(parts.day ?? 0)
    }

    private static func dayTicker(of pairId: String) -> String {
        isCoinM(pairId) ? binanceDapiDayTicker : binanceFapiDayTicker
    }

    private static func isCoinM(_ pairId: String) -> Bool { pairId.hasPrefix(binanceCoinMMark) }
}
