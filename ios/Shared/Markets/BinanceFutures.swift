import Foundation

final class BinanceFutures: Market {
    private static let urlUsdM = "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol="
    private static let urlCurrencyPairsUsdM = "https://fapi.binance.com/fapi/v1/exchangeInfo"

    private static let urlCoinM = "https://dapi.binance.com/dapi/v1/ticker/24hr?symbol="

    // Ohne symbol-Parameter liefern beide Endpunkte alle Kontrakte auf einmal.
    private static let urlAllTickersUsdM = "https://fapi.binance.com/fapi/v1/ticker/24hr"
    private static let urlAllTickersCoinM = "https://dapi.binance.com/dapi/v1/ticker/24hr"
    private static let urlCurrencyPairsCoinM = "https://dapi.binance.com/dapi/v1/exchangeInfo"

    private static let coinMPrefix = "2:"

    private static func isCoinMPair(_ pairId: String) -> Bool { pairId.hasPrefix(coinMPrefix) }

    /// Verfallsdatum im Format yyMMdd (UTC), wie `FUTURES_DATE_FORMAT`.
    private static func formatDeliveryDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMdd"
        return f.string(from: date)
    }

    private static func readTicker(_ json: JObject, _ ticker: inout Ticker) throws {
        // Bei unbekanntem Symbol antwortet Binance mit {"code":..,"msg":..}.
        // Ohne diese Abfrage endet das in "No value for volume".
        let msg = json.optString("msg")
        if !msg.isEmpty { throw JSONError(message: msg) }

        // COIN-M: «volume» zählt Kontrakte, «baseVolume» ist die Menge in der Basiswährung;
        // USD-M hat kein baseVolume, dort ist «volume» bereits die Basiswährung
        ticker.vol = try json.has("baseVolume") ? json.double("baseVolume") : json.double("volume")
        ticker.high = try json.double("highPrice")
        ticker.low = try json.double("lowPrice")
        ticker.last = try json.double("lastPrice")
        ticker.timestamp = try json.long("closeTime")

        // Optional
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        // Gleitende 24 h, schon in Prozent
        ticker.change24hPercent = Change24h.percent(json.optDouble("priceChangePercent"))
    }

    init() {
        super.init(key: "BinanceFutures", name: "Binance Futures", ttsName: "Binance Futures")
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        let fullPairId = info.pairId ?? ""
        let urlTemplate: String
        let pairId: String

        if BinanceFutures.isCoinMPair(fullPairId) {
            pairId = String(fullPairId.dropFirst(BinanceFutures.coinMPrefix.count))
            urlTemplate = BinanceFutures.urlCoinM
        } else {
            pairId = fullPairId
            urlTemplate = BinanceFutures.urlUsdM
        }

        guard let deliveryDate = info.contractType.deliveryDate else {
            return urlTemplate + pairId
        }

        let pairIdWithDeliveryDate = "\(info.base)\(info.quote)_\(BinanceFutures.formatDeliveryDate(deliveryDate))"
        return urlTemplate + pairIdWithDeliveryDate
    }

    override func parseTicker(requestId: Int, response: String, ticker: inout Ticker, info: CheckerInfo) throws {
        if BinanceFutures.isCoinMPair(info.pairId ?? "") {
            try BinanceFutures.readTicker(JArray(string: response).object(0), &ticker)
        } else {
            try BinanceFutures.readTicker(JObject(string: response), &ticker)
        }
    }

    override var bulkTickersNumOfRequests: Int { 2 }

    /// Binance liefert ohne Filter jedes gehandelte Paar.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(requestId: Int) -> String? {
        requestId == 0 ? BinanceFutures.urlAllTickersUsdM : BinanceFutures.urlAllTickersCoinM
    }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for entry in try JArray(string: response).allObjects() {
            let symbol = entry.optString("symbol")
            if symbol.isEmpty { continue }

            var ticker = Ticker()
            try BinanceFutures.readTicker(entry, &ticker)

            // COIN-M-Paare tragen dasselbe Präfix wie beim Paar-Abgleich.
            tickers[requestId > 0 ? BinanceFutures.coinMPrefix + symbol : symbol] = ticker
        }
        return tickers
    }

    override var currencyPairsNumOfRequests: Int { 2 }

    override func currencyPairsURL(requestId: Int) -> String? {
        requestId == 0 ? BinanceFutures.urlCurrencyPairsUsdM : BinanceFutures.urlCurrencyPairsCoinM
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        func parseContractType(_ value: String) -> FuturesContractType? {
            switch value {
            // TradFi-Perpetuals (Aktien, Rohstoffe, Devisen, Pre-IPO) sind technisch gewöhnliche
            // Perpetuals: gleiche Abfrage, gleiche Live-Kurse und Funding
            case "PERPETUAL", TradFi.binanceTradFiPerpetual: return .perpetual
            case "CURRENT_QUARTER": return .quarterly
            case "NEXT_QUARTER": return .biquarterly
            default: return nil
            }
        }

        var pairs: [CurrencyPairInfo] = []
        for market in try json.array("symbols").allObjects() {
            // Die App unterstützt nur diese Kontrakttypen
            let rawContractType = try market.string("contractType")
            guard let contractType = parseContractType(rawContractType) else { continue }

            let rawSymbol = try market.string("symbol")
            let symbol = requestId > 0 ? BinanceFutures.coinMPrefix + rawSymbol : rawSymbol
            let baseAsset = try market.string("baseAsset")
            let quoteAsset = try market.string("quoteAsset")

            // Immer gesetzt (auch false): so erkennt der Paarspeicher Listen aus älteren Versionen
            let tradFi = TradFi.binance(contractType: rawContractType, subTypes: TradFi.strings(market, "underlyingSubType"))
            pairs.append(CurrencyPairInfo(baseAsset, quoteAsset, symbol, contractType, tradFi: tradFi))
        }
        return pairs
    }
}
