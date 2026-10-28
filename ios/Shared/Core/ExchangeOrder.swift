import Foundation

/// Reihenfolge der Börsenauswahl: Abschnitte Spot · Futures · DEX-Pools · Forex, darin nach dem
/// Handelsvolumen am Tag der Installation (einmal von CoinGecko geholt, `ExchangeRankingStore`), ohne Ranking in der
/// Eingabe-Reihenfolge (Explorer: alphabetisch). Reine Logik — wie Android `ExchangeOrder`
/// (`testdata/parity/picker_order.json`).
enum ExchangeOrder {
    /// Öffentliche Liste der Spot-Börsen (ohne Schlüssel), Feld `trade_volume_24h_btc`.
    static let spotURL = "https://api.coingecko.com/api/v3/exchanges?per_page=250&page=1"

    /// Öffentliche Liste der Derivate-Börsen, nach Volumen.
    static let derivativesURL =
        "https://api.coingecko.com/api/v3/derivatives/exchanges?order=trade_volume_24h_btc_desc&per_page=100&page=1"

    /// Börsen-Schlüssel → Kennung bei CoinGecko: Spot aus `spotURL`, Futures aus `derivativesURL`.
    static let spotIds: [String: String] = [
        "Coinbase": "gdax", "Kraken": "kraken", "GateIo": "gate", "Binance": "binance",
        "BinanceUs": "binance_us", "Kucoin": "kucoin", "Okex": "okex", "Bybit": "bybit_spot",
        "Bitget": "bitget", "Mexc": "mxc", "Htx": "huobi", "Bitfinex": "bitfinex", "Bitstamp": "bitstamp",
        "Bitvavo": "bitvavo", "CryptoCom": "crypto_com", "Gemini": "gemini", "BingX": "bingx",
        "Woo": "wootrade", "Phemex": "phemex", "Poloniex": "poloniex", "OneTrading": "bitpanda",
        "Upbit": "upbit", "Bithumb": "bithumb", "BitFlyer": "bitflyer", "Bitbank": "bitbank",
        "Coincheck": "coincheck", "Btcturk": "btcturk", "Paribu": "paribu", "Icrypex": "icrypex",
        "Bitso": "bitso", "MercadoBitcoin": "mercado_bitcoin", "Indodax": "indodax", "ZebPay": "zebpay",
        "CoinDcx": "coindcx", "HashKey": "hashkey_exchange", "IndependentReserve": "independent_reserve",
        "BtcMarkets": "btcmarkets", "Luno": "luno", "Latoken": "latoken", "NonKyc": "nonkyc_io",
    ]

    /// Wie `spotIds` für die Futures-Abschnitte.
    static let derivativesIds: [String: String] = [
        "OkexFutures": "okex_swap", "BinanceFutures": "binance_futures", "BybitFutures": "bybit",
        "BitgetFutures": "bitget_futures", "MexcFutures": "mxc_futures", "HtxFutures": "huobi_dm",
        "BingXFutures": "bingx_futures", "WooFutures": "woo_network_futures", "PhemexFutures": "phemex_futures",
        "Hyperliquid": "hyperliquid", "Aster": "aster", "Lighter": "lighter", "Deribit": "deribit",
        "BitunixFutures": "bitunix_futures",
    ]

    /// Eine Börse der Auswahl: Schlüssel und Abschnitt.
    struct Entry: Equatable, Sendable {
        let key: String
        let category: MarketCategory
    }

    /// Volumen je Börsen-Schlüssel aus beiden Listen ([{id, trade_volume_24h_btc}, …]); nur endliche Werte ≥ 0.
    /// Unlesbare Antwort → Fehler (dann wird nichts gespeichert).
    static func parseVolumes(spotBody: String, derivativesBody: String) throws -> [String: Double] {
        var out: [String: Double] = [:]
        collect(try JArray(string: spotBody), ids: spotIds, into: &out)
        collect(try JArray(string: derivativesBody), ids: derivativesIds, into: &out)
        return out
    }

    private static func collect(_ rows: JArray, ids: [String: String], into out: inout [String: Double]) {
        var byId: [String: Double] = [:]
        for row in rows.objects {
            let volume = row.optDouble("trade_volume_24h_btc")
            let id = row.optString("id")
            if volume.isFinite, volume >= 0, byId[id] == nil { byId[id] = volume }
        }
        for (key, id) in ids {
            if let volume = byId[id] { out[key] = volume }
        }
    }

    /// Abschnitte in der Reihenfolge von `MarketCategory` (leere fallen weg). Mit `volumes` darin absteigend nach
    /// Volumen (gleiches Volumen: Eingabe-Reihenfolge), Börsen ohne Wert danach in Eingabe-Reihenfolge.
    static func sections(_ markets: [Entry], volumes: [String: Double]?) -> [(category: MarketCategory, keys: [String])] {
        MarketCategory.allCases.compactMap { category -> (category: MarketCategory, keys: [String])? in
            let inSection = markets.filter { $0.category == category }.map(\.key)
            if inSection.isEmpty { return nil }
            guard let volumes else { return (category, inSection) }
            let ranked = inSection.enumerated().filter { volumes[$0.element] != nil }
            // Stabil: gleiches Volumen behält die Eingabe-Reihenfolge
            let sorted = ranked.sorted { left, right in
                let a = volumes[left.element] ?? 0, b = volumes[right.element] ?? 0
                return a != b ? a > b : left.offset < right.offset
            }.map(\.element)
            return (category, sorted + inSection.filter { volumes[$0] == nil })
        }
    }

    /// Volumenrang je Börse über alle Abschnitte (0 = grösstes Volumen; gleiches Volumen: Schlüssel alphabetisch);
    /// ohne Ranking leer. Für die Coin-Suche (`ExplorerCoinPairPreference.exchangeRank`). Wie Android.
    static func ranks(_ volumes: [String: Double]?) -> [String: Int] {
        guard let volumes else { return [:] }
        let ranked = volumes.keys.sorted { left, right in
            let a = volumes[left] ?? 0, b = volumes[right] ?? 0
            return a != b ? a > b : left < right
        }
        return Dictionary(ranked.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { a, _ in a })
    }

    /// Die Börsen in Abschnitten (für die Auswahl), mit gespeichertem Ranking falls vorhanden; ohne Ranking bzw. ohne
    /// Volumen gilt die Reihenfolge von `markets` (Explorer: alphabetisch nach Name, wie Android).
    static func sections(of markets: [Market], volumes: [String: Double]?) -> [(category: MarketCategory, markets: [Market])] {
        let byKey = Dictionary(markets.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        return sections(markets.map { Entry(key: $0.key, category: $0.category) }, volumes: volumes).map { section in
            (section.category, section.keys.compactMap { byKey[$0] })
        }
    }
}

/// Gespeichertes Ranking der Börsen (`ExchangeOrder.parseVolumes`), Stand des ersten erfolgreichen Abrufs nach der
/// Installation; nicht in der Sicherung. Fehlt es, holt `ensureLoaded` beide Listen höchstens einmal je App-Sitzung
/// (beim Öffnen des Explorers); danach nie wieder. Wie Android `ExchangeRankingStore`.
@MainActor
enum ExchangeRankingStore {
    nonisolated static let volumesKey = "exchangeRanking.volumes"
    nonisolated static let dateKey = "exchangeRanking.date"

    /// Schon versucht in dieser Sitzung (Erfolg oder nicht).
    private static var triedThisSession = false

    /// Gespeichertes Ranking; nil = noch keins (alphabetische Auswahl).
    nonisolated static func volumes(defaults: UserDefaults = .standard) -> [String: Double]? {
        guard let raw = defaults.dictionary(forKey: volumesKey) else { return nil }
        var out: [String: Double] = [:]
        for (key, value) in raw {
            if let number = value as? NSNumber, number.doubleValue.isFinite { out[key] = number.doubleValue }
        }
        return out.isEmpty ? nil : out
    }

    /// Speichert ein Ranking samt Datum (ms).
    nonisolated static func save(_ volumes: [String: Double], nowMillis: Int64 = EpochTime.nowMillis,
                                 defaults: UserDefaults = .standard) {
        defaults.set(volumes, forKey: volumesKey)
        defaults.set(NSNumber(value: nowMillis), forKey: dateKey)
    }

    /// Holt das Ranking, falls es fehlt und in dieser Sitzung noch nicht versucht wurde. true = neu gespeichert.
    @discardableResult
    static func ensureLoaded(defaults: UserDefaults = .standard) async -> Bool {
        if triedThisSession || volumes(defaults: defaults) != nil { return false }
        triedThisSession = true
        do {
            async let spot = MarketHTTP.call(ExchangeOrder.spotURL, session: MarketHTTP.bulkSession)
            async let derivatives = MarketHTTP.call(ExchangeOrder.derivativesURL, session: MarketHTTP.bulkSession)
            let parsed = try ExchangeOrder.parseVolumes(spotBody: try await spot, derivativesBody: try await derivatives)
            if parsed.isEmpty { return false }
            save(parsed, defaults: defaults)
            return true
        } catch {
            HttpLog.add("Börsen-Ranking nicht geladen: \(error.localizedDescription)")
            return false
        }
    }
}
