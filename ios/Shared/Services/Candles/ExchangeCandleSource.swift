import Foundation

/// Paar für Kerzen von der EIGENEN Börse: Schlüssel und Name der Börse, Kürzel, Kennung der Börse
/// (`Watch.pairId`, z. B. Kraken «XXBTZUSD», OKX «BTC-USDT-SWAP», Binance COIN-M «2:BTCUSD_PERP») und
/// Vertragsart. `tradFi` = Aktie, Rohstoff, Devise: nie Kerzen eines gleichnamigen Krypto-Tokens.
/// Wie `CandlePair.kt`.
struct CandlePair: Equatable, Sendable {
    static let dexMarketKey = "DexScreener"

    let marketKey: String
    let marketName: String
    let base: String
    let quote: String
    var pairId: String? = nil
    var contractType: FuturesContractType = .none
    var tradFi: Bool = false

    var isFutures: Bool { contractType != .none }

    /// Laufzeit-Kontrakt (Woche … Quartal) mit Verfallstag: nie Kerzen des Perpetuals oder des Spots.
    var isDated: Bool { contractType.deliveryDate != nil }

    /// Alpha-Paar von Binance (Kennung «ALPHA_<id>USDT», `BinanceAlpha`).
    var isBinanceAlpha: Bool { BinanceAlpha.isAlphaPair(marketKey: marketKey, pairId: pairId) }

    /// DexScreener-Pool: keine Börsen-Kerzen (fremde Kerzen gehörten nicht zum gemerkten Pool).
    var isDex: Bool { marketKey.caseInsensitiveCompare(Self.dexMarketKey) == .orderedSame }

    /// Schlüssel für Zwischenspeicher: Börse, Kennung bzw. Kürzel, Vertragsart (wie Android).
    var cacheKey: String {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        return "\(marketKey)|\(pairId ?? "\(b)/\(q)")|\(contractType.backupName)"
    }

    init(marketKey: String, marketName: String, base: String, quote: String, pairId: String? = nil,
         contractType: FuturesContractType = .none, tradFi: Bool = false) {
        self.marketKey = marketKey
        self.marketName = marketName
        self.base = base
        self.quote = quote
        self.pairId = pairId
        self.contractType = contractType
        self.tradFi = tradFi
    }

    init(watch: Watch, tradFi: Bool) {
        self.init(marketKey: watch.marketKey, marketName: watch.marketName, base: watch.baseAsset,
                  quote: watch.quoteAsset, pairId: watch.pairId, contractType: watch.contractType, tradFi: tradFi)
    }

    /// Paar der Merkliste mit TradFi-Kennung aus der gespeicherten Liste der TradFi-Paare oder der
    /// Gruppe «TradFi» (wie `Watch.assetKind`; Android `CandlePairs.of`).
    static func of(_ watch: Watch) -> CandlePair {
        CandlePair(watch: watch, tradFi: watch.groupName == AutoGroup.tradFi
                   || CoinLogoStore.tradFiPairs.contains(watch.logoPairKey))
    }
}

/// Anfragen für eine Kerzenquelle: GET (`await http(url)`), POST (JSON, z. B. Hyperliquid) — je nil bei
/// Fehler, Sperre oder Pause — und die Uhrzeit `now` für Zeitfenster in der Adresse (Tests setzen sie fest).
/// Wie `CandleHttp` (Android).
struct CandleHTTP: Sendable {
    let get: @Sendable (String) async -> String?
    var post: @Sendable (String, String) async -> String? = { _, _ in nil }
    var now: Int64 = TimeUtils.nowMillis

    func callAsFunction(_ url: String) async -> String? { await get(url) }
}

/// Kerzen einer Börse über ihren öffentlichen Endpunkt ohne Schlüssel. Erweiterung: neue Quelle als
/// `struct` in ExchangeCandleSourcesMajor.swift schreiben und in `ExchangeCandleRegistry.sources` eintragen
/// (Gegenstück in ExchangeCandleSourcesMajor.kt), dazu ein Fall in testdata/parity/own_candles.json.
protocol ExchangeCandleSource: Sendable {
    /// Börsen-Schlüssel (`Market.key`), für die die Quelle gilt.
    var marketKeys: Set<String> { get }

    /// Kann die Quelle dieses Paar liefern (Vertragsart, Kennung)?
    func supports(_ pair: CandlePair) -> Bool

    /// Bis zu `limit` Kerzen (`.h1`, `.h4` oder `.d1`), zeitlich aufsteigend, Startzeit in Epoch-ms
    /// (UTC); nil = keine. Anfragen nur über `http`.
    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]?

    /// Antwort mit HTTP 200, die trotzdem «zu viele Anfragen» meldet (Kraken, Bybit, OKX).
    func isRateLimited(_ body: String) -> Bool
}

extension ExchangeCandleSource {
    func isRateLimited(_ body: String) -> Bool { false }
}

/// Alle Quellen für eigene Kerzen; Auswahl je Paar. Wie `ExchangeCandleRegistry` (Android).
enum ExchangeCandleRegistry {
    static let sources: [any ExchangeCandleSource] = [
        BinanceAlphaCandles(),
        BinanceCandles(),
        BinanceUsCandles(),
        BinanceFuturesCandles(),
        CoinbaseCandles(),
        KrakenCandles(),
        BitstampCandles(),
        BitvavoCandles(),
        BybitCandles(),
        OkxCandles(),
        // Zweite Runde (ExchangeCandleSourcesGlobal.swift, ab Upbit ExchangeCandleSourcesSpecial.swift)
        BitfinexCandles(),
        BitgetCandles(),
        GateIoCandles(),
        KucoinCandles(),
        MexcCandles(),
        MexcFuturesCandles(),
        HtxCandles(),
        GeminiCandles(),
        CryptoComCandles(),
        UpbitCandles(),
        WooCandles(),
        PoloniexCandles(),
        DeribitCandles(),
        HyperliquidCandles(),
        OneTradingCandles(),
    ]

    /// Intervalle mit eigenen Kerzen (alle Zeiträume von Aktionsblatt, Mini-Chart und Widget).
    static let intervals: Set<CandleInterval> = [.h1, .h4, .d1]

    static func source(for pair: CandlePair) -> (any ExchangeCandleSource)? {
        sources.first { $0.marketKeys.contains(pair.marketKey) && $0.supports(pair) }
    }

    static func supports(_ pair: CandlePair, interval: CandleInterval) -> Bool {
        intervals.contains(interval) && source(for: pair) != nil
    }
}

/// Herkunft der Kerzen eines Paars: zuerst die eigene Börse (`ExchangeCandleRegistry`); sonst die
/// Binance-Kette mit Hinweis («Chart: Binance») und Prüfung auf gleichen Token. TradFi-Paare und
/// DEX-Pools nie mit fremden Kerzen (gleiches Kürzel ≠ gleicher Wert), Laufzeit-Kontrakte nie mit
/// denen des Perpetuals. Wie `OwnCandlePolicy` (Android).
enum OwnCandlePolicy {
    /// Darf die Ausweich-Kette (Binance … Coinbase) einspringen? Nicht für Alpha-Paare von Binance: Ein
    /// gleiches Kürzel an Binance Spot oder Coinbase wäre ein anderer Token (Alpha = dort nicht gelistet).
    static func allowsFallback(_ pair: CandlePair) -> Bool {
        !pair.tradFi && !pair.isDex && !pair.isDated && !pair.isBinanceAlpha
    }

    /// Gleicher Token? Reihe der Ausweich-Kette gilt nur, wenn ihr letzter Schluss höchstens
    /// `DayChange.maxPriceGap` (25 %) vom Kurs des Paars `price` abweicht — wie der Chart
    /// (`SheetChart.accept`). Ohne Kurs nicht prüfbar → false.
    static func acceptsFallback(_ candles: [MarketCandle]?, price: Double?) -> Bool {
        guard let p = price, p.isFinite, p > 0,
              let last = candles?.last(where: { $0.close.isFinite && $0.close > 0 })?.close else { return false }
        return abs(p / last - 1) <= DayChange.maxPriceGap
    }

    /// Anbieter eigener Kerzen = Name der Börse des Paars — der Hinweis in der Zeile entfällt damit; bei
    /// Alpha-Paaren von Binance «Binance Alpha» (`BinanceAlpha.provider`).
    static func provider(_ pair: CandlePair) -> String { pair.isBinanceAlpha ? BinanceAlpha.provider : pair.marketName }
}
