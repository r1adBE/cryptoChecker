import Foundation

/// Startpaar der leeren Merkliste: Name (nicht übersetzt) und Symbol.
struct StarterCoin: Identifiable, Equatable, Codable, Sendable {
    let name: String
    let symbol: String
    var id: String { symbol }
}

/// Eintrag aus CoinGecko `/coins/markets` (nur die Felder, die die Auswahl braucht).
struct StarterMarketCoin: Equatable, Sendable {
    let symbol: String
    let name: String
    /// Rang nach Marktkapitalisierung; nil = unbekannt (kommt nach allen bekannten).
    let rank: Int?
    /// Marktkapitalisierung in USD (nur für «Heute auffällig»).
    var marketCap: Double? = nil
}

/// Coin für «Heute auffällig» (`MarketUniverse` in Android).
struct UniverseCoin: Codable, Equatable, Sendable {
    let symbol: String
    let name: String
    let marketCap: Double?
}

/// Startpaare der leeren Merkliste: die fünf grössten Coins nach Marktkapitalisierung,
/// ohne Stablecoins und ohne «verpackte»/gestakte Doppelgänger — wie `StarterPairs.kt`.
///
/// Die reine Logik (Filtern, fünf wählen, Ersatzliste) steht in den statischen
/// Funktionen ohne Netz; `load(marketKey:)` holt die Daten. Startbörse siehe `StarterPairs`.
///
/// Datenschutz: Von CoinGecko (bereits aufgeführter Anbieter) wird nur die öffentliche
/// Coin-Liste geholt — ohne Schlüssel, ohne Bezug zur Merkliste. Für die Börse nur die
/// öffentliche Kursliste, falls die Paarliste noch nicht zwischengespeichert ist.
enum StarterCoins {
    static let count = 5

    /// Gilt 24 Stunden.
    static let maxAge: TimeInterval = 24 * 3600

    /// Je Startbörse ein Eintrag (wie Android `coins_<Börse>` / `time_<Börse>`), damit ein
    /// Regionswechsel die Liste der anderen Börse nicht verwirft.
    private static func cacheKey(_ marketKey: String) -> String { "starter_coins_" + marketKey }
    private static func cacheAtKey(_ marketKey: String) -> String { "starter_coins_time_" + marketKey }

    static let marketsURL = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=30&page=1"

    // MARK: Ersatzliste

    /// Beim Laden und bei jedem Fehler: Binance-Spot.
    static let fallback: [StarterCoin] = [
        StarterCoin(name: "Bitcoin", symbol: "BTC"),
        StarterCoin(name: "Ethereum", symbol: "ETH"),
        StarterCoin(name: "XRP", symbol: "XRP"),
        StarterCoin(name: "BNB", symbol: "BNB"),
        StarterCoin(name: "Solana", symbol: "SOL"),
    ]

    /// Binance.US (Region USA, …/USDT): ohne XRP, das Binance.US nicht sicher führt — wie Android.
    static let fallbackUS: [StarterCoin] = [
        StarterCoin(name: "Bitcoin", symbol: "BTC"),
        StarterCoin(name: "Ethereum", symbol: "ETH"),
        StarterCoin(name: "BNB", symbol: "BNB"),
        StarterCoin(name: "Solana", symbol: "SOL"),
        StarterCoin(name: "Dogecoin", symbol: "DOGE"),
    ]

    static func fallback(marketKey: String) -> [StarterCoin] {
        marketKey == StarterPairs.binanceKey ? fallback : fallbackUS
    }

    /// Startbörse der Geräte-Region (siehe `StarterPairs.marketKey`).
    static func marketKey(_ locale: Locale = .current) -> String {
        StarterPairs.marketKey(StarterPairs.region(locale))
    }

    // MARK: Filter (gemeinsam mit «Was gerade auffällt», siehe `CoinExclusion`)

    static var stablecoins: Set<String> { CoinExclusion.stablecoins }
    static var wrapped: Set<String> { CoinExclusion.wrapped }

    static func isExcluded(symbol: String, name: String) -> Bool {
        CoinExclusion.isExcluded(symbol: symbol, name: name)
    }

    // MARK: Auswahl

    /// Die ersten `count` Coins nach Rang, ohne ausgeschlossene, ohne doppelte Symbole
    /// und nur solche, für die `available(SYMBOL)` gilt. Weniger als `count` → nil
    /// (dann gilt die Ersatzliste).
    static func pick(_ coins: [StarterMarketCoin], count: Int = StarterCoins.count,
                     available: (String) -> Bool) -> [StarterCoin]? {
        let ordered = coins.enumerated().sorted { a, b in
            switch (a.element.rank, b.element.rank) {
            case let (x?, y?) where x != y: return x < y
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.offset < b.offset
            }
        }.map { $0.element }
        var seen = Set<String>()
        var out: [StarterCoin] = []
        for coin in ordered {
            let symbol = coin.symbol.trimmingCharacters(in: .whitespaces).uppercased()
            let name = coin.name.trimmingCharacters(in: .whitespaces)
            guard !isExcluded(symbol: symbol, name: name), seen.insert(symbol).inserted,
                  available(symbol) else { continue }
            out.append(StarterCoin(name: name.isEmpty ? symbol : name, symbol: symbol))
            if out.count == count { return out }
        }
        return nil
    }

    /// CoinGecko `/coins/markets`: `[{"symbol":"btc","name":"Bitcoin","market_cap_rank":1,…}]`.
    static func parseMarkets(_ text: String) throws -> [StarterMarketCoin] {
        try JArray(string: text).objects.compactMap { o in
            let symbol = o.optString("symbol")
            guard !symbol.isEmpty else { return nil }
            let rank = o.optLong("market_cap_rank", -1)
            let cap = o.optDouble("market_cap")
            return StarterMarketCoin(symbol: symbol, name: o.optString("name"), rank: rank > 0 ? Int(rank) : nil,
                                     marketCap: cap.isFinite && cap > 0 ? cap : nil)
        }
    }

    /// Basis-Symbole mit Binance-Spotpaar `<SYM>USDT` aus `/api/v3/ticker/price`.
    static func parseBinanceBases(_ text: String) throws -> Set<String> {
        var out = Set<String>()
        for o in try JArray(string: text).objects {
            let symbol = o.optString("symbol").uppercased()
            if symbol.hasSuffix("USDT"), symbol.count > 4 { out.insert(String(symbol.dropLast(4))) }
        }
        return out
    }

    // MARK: Zwischenspeicher

    /// Zuletzt geholte Liste derselben Startbörse (auch wenn älter als 24 h); sonst nil.
    static func cached(marketKey: String, store: UserDefaults = .standard) -> [StarterCoin]? {
        guard let data = store.data(forKey: cacheKey(marketKey)),
              let coins = try? JSONDecoder().decode([StarterCoin].self, from: data),
              coins.count == count, Set(coins.map(\.symbol)).count == count else { return nil }
        return coins
    }

    /// Sofort zeigen: Zwischenspeicher, sonst Ersatzliste.
    static func initial(marketKey: String, store: UserDefaults = .standard) -> [StarterCoin] {
        cached(marketKey: marketKey, store: store) ?? fallback(marketKey: marketKey)
    }

    static func isFresh(marketKey: String, store: UserDefaults = .standard, now: Date = Date()) -> Bool {
        guard cached(marketKey: marketKey, store: store) != nil else { return false }
        let age = now.timeIntervalSince1970 - store.double(forKey: cacheAtKey(marketKey))
        return age >= 0 && age < maxAge
    }

    private static func save(_ coins: [StarterCoin], marketKey: String, store: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(coins) else { return }
        store.set(data, forKey: cacheKey(marketKey))
        store.set(Date().timeIntervalSince1970, forKey: cacheAtKey(marketKey))
    }

    // MARK: Laden

    /// Frische Liste holen und speichern; nil bei jedem Fehler (dann bleibt die bisherige).
    static func load(marketKey: String) async -> [StarterCoin]? {
        guard let text = try? await MarketHTTP.call(marketsURL),
              let coins = try? parseMarkets(text), !coins.isEmpty,
              let bases = await availableBases(marketKey: marketKey) else { return nil }
        guard let picked = pick(coins, available: { bases.contains($0) }) else { return nil }
        save(picked, marketKey: marketKey)
        return picked
    }

    // MARK: «Heute auffällig»

    /// Etwas mehr als 30, damit nach Stablecoins und Doppelgängern rund 30 bleiben.
    static let universeURL = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=40&page=1"
    static let universeCount = 30
    static let universeMinCount = 8
    private static let universeKey = "market_universe_binance"
    private static let universeAtKey = "market_universe_time_binance"

    /// Die ersten 30 Coins nach Rang (gleiche Filter wie die Startpaare), nur mit Binance-Paar …USDT;
    /// nil unter 8 Coins. Wie `MarketUniverse.pick` (Android). Die Startpaare (fünf) bleiben unberührt.
    static func pickUniverse(_ coins: [StarterMarketCoin], available: (String) -> Bool) -> [UniverseCoin]? {
        let ordered = coins.enumerated().sorted { a, b in
            switch (a.element.rank, b.element.rank) {
            case let (x?, y?) where x != y: return x < y
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.offset < b.offset
            }
        }.map { $0.element }
        var seen = Set<String>()
        var out: [UniverseCoin] = []
        for coin in ordered {
            let symbol = coin.symbol.trimmingCharacters(in: .whitespaces).uppercased()
            let name = coin.name.trimmingCharacters(in: .whitespaces)
            guard !isExcluded(symbol: symbol, name: name), seen.insert(symbol).inserted,
                  available(symbol) else { continue }
            out.append(UniverseCoin(symbol: symbol, name: name.isEmpty ? symbol : name, marketCap: coin.marketCap))
            if out.count == universeCount { break }
        }
        return out.count >= universeMinCount ? out : nil
    }

    /// Coins für «Heute auffällig» (Binance …USDT, unabhängig von der Region): 24 h
    /// zwischengespeichert (ein CoinGecko-Abruf am Tag); `force` = neu ermitteln. Bei Fehlern
    /// der letzte Stand (auch abgelaufen), sonst nil.
    static func universe(force: Bool = false, store: UserDefaults = .standard) async -> [UniverseCoin]? {
        let cached = store.data(forKey: universeKey).flatMap { try? JSONDecoder().decode([UniverseCoin].self, from: $0) }
        let age = Date().timeIntervalSince1970 - store.double(forKey: universeAtKey)
        if !force, let cached, cached.count >= universeMinCount, age >= 0, age < maxAge { return cached }
        guard let text = try? await MarketHTTP.call(universeURL),
              let coins = try? parseMarkets(text), !coins.isEmpty,
              let bases = await availableBases(marketKey: StarterPairs.binanceKey),
              let picked = pickUniverse(coins, available: { bases.contains($0) }) else { return cached }
        if let data = try? JSONEncoder().encode(picked) {
            store.set(data, forKey: universeKey)
            store.set(Date().timeIntervalSince1970, forKey: universeAtKey)
        }
        return picked
    }

    /// Basis-Symbole mit Startpaar an der Startbörse: aus der Paarliste des
    /// der Seite «Paar hinzufügen», falls schon geladen, sonst eine öffentliche Kursliste, zuletzt
    /// die mitgelieferte Paarliste der Börse (wie Android `availableBases`).
    private static func availableBases(marketKey: String) async -> Set<String>? {
        let us = marketKey == StarterPairs.binanceUsKey
        let quote = StarterPairs.startQuoteValue
        let cached = await PairCache.shared.pairs(for: marketKey)
        let known = Set(cached.pairs
            .filter { $0.contractType == .none && $0.quote.uppercased() == quote }
            .map { $0.base.uppercased() })
        if cached.lastSyncDate > 0, !known.isEmpty { return known }
        var looked: Set<String>?
        // Öffentlicher Marktdaten-Spiegel von Binance bzw. Binance.US (wie Android), alle Kurse in einem Aufruf
        let url = us ? "https://api.binance.us/api/v3/ticker/price" : "https://data-api.binance.vision/api/v3/ticker/price"
        if let text = try? await MarketHTTP.call(url, session: MarketHTTP.bulkSession) {
            looked = try? parseBinanceBases(text)
        }
        if let looked, !looked.isEmpty { return looked }
        return known.isEmpty ? nil : known
    }
}

// MARK: Kurse für die Auswahl

/// Aktueller Kurs und 24-h-Veränderung (Prozent) eines Startcoins in der Start-Quote.
/// `quote`: eigene Quote des Coins; nil = Start-Quote der Region (Binance/Binance.US: immer nil).
/// `sinceUtcDay`: `change` ist die Veränderung seit 00:00 UTC statt über gleitende 24 h — die Zeile nennt das
/// sichtbar («Seit 00:00 UTC»). Die Startbörsen liefern gleitende 24 h, also derzeit immer false. Wie Android.
struct StarterPrice: Equatable, Sendable {
    let price: Double
    let change: Double?
    var quote: String? = nil
    var sinceUtcDay: Bool = false
}

/// Kurse der Startcoins für die Auswahl — eine Abfrage für alle (Binance-Spiegel
/// `ticker/24hr?symbols=[…]`, in der Region USA Binance.US mit derselben Schnittstelle).
/// 60 s im Speicher; Fehler → leer (die Auswahl geht trotzdem).
actor StarterPriceSource {
    static let shared = StarterPriceSource()
    static let maxAgeMillis: Int64 = 60_000
    /// Harte Zeitgrenze für alle Kurse zusammen.
    static let timeoutSeconds: Double = 10

    private var cache: (key: String, at: Int64, prices: [String: StarterPrice])?

    private static func cacheKey(_ symbols: [String], region: String?) -> String {
        StarterPairs.marketKey(region) + ":" + symbols.joined(separator: ",")
    }

    /// Noch gültige Kurse aus dem Speicher; sonst nil.
    func cached(symbols: [String], region: String?, now: Int64 = TimeUtils.nowMillis) -> [String: StarterPrice]? {
        guard let cache, cache.key == Self.cacheKey(symbols, region: region),
              now - cache.at >= 0, now - cache.at < Self.maxAgeMillis else { return nil }
        return cache.prices
    }

    /// Symbol (Grossbuchstaben) → Kurs; leer, wenn nichts kam.
    func prices(symbols: [String], region: String?) async -> [String: StarterPrice] {
        if let hit = cached(symbols: symbols, region: region) { return hit }
        let loaded = (try? await AsyncTimeout.run(seconds: Self.timeoutSeconds) {
            await Self.load(symbols: symbols, region: region)
        }) ?? [:]
        if !loaded.isEmpty {
            cache = (Self.cacheKey(symbols, region: region), TimeUtils.nowMillis, loaded)
        }
        return loaded
    }

    private static func load(symbols: [String], region: String?) async -> [String: StarterPrice] {
        let list = "[" + symbols.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        let host = StarterPairs.isUS(region) ? "https://api.binance.us" : "https://data-api.binance.vision"
        let url = host + "/api/v3/ticker/24hr?symbols=" + list.urlQueryEncoded
        guard let text = try? await MarketHTTP.call(url) else { return [:] }
        return (try? Self.parseBinance24h(text)) ?? [:]
    }

    /// Binance `ticker/24hr?symbols=[…]` → Coin → (lastPrice, priceChangePercent).
    static func parseBinance24h(_ text: String) throws -> [String: StarterPrice] {
        var out: [String: StarterPrice] = [:]
        for item in try JArray(string: text).objects {
            let symbol = item.optString("symbol").uppercased()
            let price = item.optDouble("lastPrice")
            guard symbol.hasSuffix("USDT"), symbol.count > 4, price.isFinite, price > 0 else { continue }
            let change = item.optDouble("priceChangePercent")
            out[String(symbol.dropLast(4))] = StarterPrice(price: price, change: change.isFinite ? change : nil)
        }
        return out
    }

}
