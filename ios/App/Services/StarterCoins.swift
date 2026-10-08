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
/// Funktionen ohne Netz; `load(us:)` holt die Daten.
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
    private static func cacheKey(_ us: Bool) -> String { "starter_coins_" + marketKey(us) }
    private static func cacheAtKey(_ us: Bool) -> String { "starter_coins_time_" + marketKey(us) }

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

    /// Coinbase (Region USA): BNB gibt es dort nicht.
    static let fallbackUS: [StarterCoin] = [
        StarterCoin(name: "Bitcoin", symbol: "BTC"),
        StarterCoin(name: "Ethereum", symbol: "ETH"),
        StarterCoin(name: "XRP", symbol: "XRP"),
        StarterCoin(name: "Solana", symbol: "SOL"),
        StarterCoin(name: "Dogecoin", symbol: "DOGE"),
    ]

    static func fallback(us: Bool) -> [StarterCoin] { us ? fallbackUS : fallback }

    /// Geräte-Region USA → Coinbase `<COIN>/USD`, sonst Binance `<COIN>/USDT` (wie `AppData.starterPair`).
    static func isUS(_ locale: Locale = .current) -> Bool {
        locale.region?.identifier.uppercased() == "US"
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

    /// Basis-Symbole mit handelbarem Coinbase-Paar `<SYM>-USD` aus `/products`.
    static func parseCoinbaseBases(_ text: String) throws -> Set<String> {
        var out = Set<String>()
        for o in try JArray(string: text).objects {
            guard o.optString("quote_currency").uppercased() == "USD",
                  !o.optBool("trading_disabled"),
                  o.optString("status", "online").lowercased() == "online" else { continue }
            let base = o.optString("base_currency").uppercased()
            if !base.isEmpty { out.insert(base) }
        }
        return out
    }

    // MARK: Zwischenspeicher

    /// Startbörse: Coinbase in der Region USA, sonst Binance (Schlüssel wie `AppData.starterPair`).
    private static func marketKey(_ us: Bool) -> String { us ? "Coinbase" : "Binance" }

    /// Zuletzt geholte Liste derselben Startbörse (auch wenn älter als 24 h); sonst nil.
    static func cached(us: Bool, store: UserDefaults = .standard) -> [StarterCoin]? {
        guard let data = store.data(forKey: cacheKey(us)),
              let coins = try? JSONDecoder().decode([StarterCoin].self, from: data),
              coins.count == count, Set(coins.map(\.symbol)).count == count else { return nil }
        return coins
    }

    /// Sofort zeigen: Zwischenspeicher, sonst Ersatzliste.
    static func initial(us: Bool, store: UserDefaults = .standard) -> [StarterCoin] {
        cached(us: us, store: store) ?? fallback(us: us)
    }

    static func isFresh(us: Bool, store: UserDefaults = .standard, now: Date = Date()) -> Bool {
        guard cached(us: us, store: store) != nil else { return false }
        let age = now.timeIntervalSince1970 - store.double(forKey: cacheAtKey(us))
        return age >= 0 && age < maxAge
    }

    private static func save(_ coins: [StarterCoin], us: Bool, store: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(coins) else { return }
        store.set(data, forKey: cacheKey(us))
        store.set(Date().timeIntervalSince1970, forKey: cacheAtKey(us))
    }

    // MARK: Laden

    /// Frische Liste holen und speichern; nil bei jedem Fehler (dann bleibt die bisherige).
    static func load(us: Bool) async -> [StarterCoin]? {
        guard let text = try? await MarketHTTP.call(marketsURL),
              let coins = try? parseMarkets(text), !coins.isEmpty,
              let bases = await availableBases(us: us) else { return nil }
        guard let picked = pick(coins, available: { bases.contains($0) }) else { return nil }
        save(picked, us: us)
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
              let bases = await availableBases(us: false),
              let picked = pickUniverse(coins, available: { bases.contains($0) }) else { return cached }
        if let data = try? JSONEncoder().encode(picked) {
            store.set(data, forKey: universeKey)
            store.set(Date().timeIntervalSince1970, forKey: universeAtKey)
        }
        return picked
    }

    /// Basis-Symbole mit Startpaar an der Startbörse: aus der Paarliste des
    /// Hinzufügen-Tabs, falls schon geladen, sonst eine öffentliche Kursliste, zuletzt
    /// die mitgelieferte Paarliste der Börse (wie Android `availableBases`).
    private static func availableBases(us: Bool) async -> Set<String>? {
        let quote = us ? "USD" : "USDT"
        let cached = await PairCache.shared.pairs(for: marketKey(us))
        let known = Set(cached.pairs
            .filter { $0.contractType == .none && $0.quote.uppercased() == quote }
            .map { $0.base.uppercased() })
        if cached.lastSyncDate > 0, !known.isEmpty { return known }
        var looked: Set<String>?
        if us {
            if let text = try? await MarketHTTP.call("https://api.exchange.coinbase.com/products",
                                                     session: MarketHTTP.bulkSession) {
                looked = try? parseCoinbaseBases(text)
            }
        } else if let text = try? await MarketHTTP.call("https://data-api.binance.vision/api/v3/ticker/price",
                                                        session: MarketHTTP.bulkSession) {
            // Öffentlicher Marktdaten-Spiegel von Binance (wie Android), gleiche Antwort wie api.binance.com
            looked = try? parseBinanceBases(text)
        }
        if let looked, !looked.isEmpty { return looked }
        return known.isEmpty ? nil : known
    }
}

// MARK: Kurse für die Auswahl

/// Aktueller Kurs und 24-h-Veränderung (Prozent) eines Startcoins in der Start-Quote.
struct StarterPrice: Equatable, Sendable {
    let price: Double
    let change: Double?
}

/// Kurse der Startcoins für die Auswahl — eine Abfrage für alle (Binance-Spiegel
/// `ticker/24hr?symbols=[…]`), in der Region USA Coinbase `/products/<SYM>-USD/stats`
/// je Coin parallel. 60 s im Speicher; Fehler → leer (die Auswahl geht trotzdem).
actor StarterPriceSource {
    static let shared = StarterPriceSource()
    static let maxAgeMillis: Int64 = 60_000
    /// Harte Zeitgrenze für alle Kurse zusammen.
    static let timeoutSeconds: Double = 10

    private var cache: (key: String, at: Int64, prices: [String: StarterPrice])?

    private static func cacheKey(_ symbols: [String], us: Bool) -> String {
        (us ? "US:" : "") + symbols.joined(separator: ",")
    }

    /// Noch gültige Kurse aus dem Speicher; sonst nil.
    func cached(symbols: [String], us: Bool, now: Int64 = TimeUtils.nowMillis) -> [String: StarterPrice]? {
        guard let cache, cache.key == Self.cacheKey(symbols, us: us),
              now - cache.at >= 0, now - cache.at < Self.maxAgeMillis else { return nil }
        return cache.prices
    }

    /// Symbol (Grossbuchstaben) → Kurs; leer, wenn nichts kam.
    func prices(symbols: [String], us: Bool) async -> [String: StarterPrice] {
        if let hit = cached(symbols: symbols, us: us) { return hit }
        let loaded = (try? await AsyncTimeout.run(seconds: Self.timeoutSeconds) {
            await Self.load(symbols: symbols, us: us)
        }) ?? [:]
        if !loaded.isEmpty {
            cache = (Self.cacheKey(symbols, us: us), TimeUtils.nowMillis, loaded)
        }
        return loaded
    }

    private static func load(symbols: [String], us: Bool) async -> [String: StarterPrice] {
        if us {
            return await withTaskGroup(of: (String, StarterPrice)?.self) { group in
                for symbol in symbols {
                    group.addTask {
                        guard let text = try? await MarketHTTP.call(
                            "https://api.exchange.coinbase.com/products/\(symbol)-USD/stats"),
                              let price = try? Self.parseCoinbaseStats(text) else { return nil }
                        return (symbol, price)
                    }
                }
                var out: [String: StarterPrice] = [:]
                for await item in group { if let item { out[item.0] = item.1 } }
                return out
            }
        }
        let list = "[" + symbols.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        let url = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols=" + list.urlQueryEncoded
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

    /// Coinbase `/products/X-USD/stats` → letzter Kurs und (last / open − 1) · 100.
    static func parseCoinbaseStats(_ text: String) throws -> StarterPrice {
        let json = try JObject(string: text)
        let last = try json.double("last")
        guard last.isFinite, last > 0 else { throw JSONError(message: "Coinbase-Stats ohne Kurs") }
        let open = json.optDouble("open")
        let change: Double? = open.isFinite && open > 0 ? (last / open - 1) * 100 : nil
        return StarterPrice(price: last, change: change)
    }
}
