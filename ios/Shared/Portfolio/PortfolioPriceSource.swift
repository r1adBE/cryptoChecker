import Foundation

/// Führt Abläufe nacheinander aus (wie ein `Mutex.withLock` in Kotlin):
/// Ein zweiter Aufruf wartet, bis der vorherige fertig ist.
actor PortfolioSerialGate {
    private var tail: Task<Void, Never>?

    func run<T: Sendable>(_ operation: @escaping @Sendable () async -> T) async -> T {
        let previous = tail
        let task = Task<T, Never> {
            _ = await previous?.value
            return await operation()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }
}

/// Aktuelle USDT-Kurse je Coin und der Zeitpunkt der ältesten verwendeten Abfrage.
struct PortfolioPrices: Sendable, Equatable {
    var prices: [String: Double] = [:]
    /// 0 = noch keine Kurse.
    var updatedAt: Int64 = 0
}

/// Kurse und Coin-Liste für das Portfolio, immer in USDT — wie `PortfolioPriceSource.kt`.
///
/// Kurse: Sammelabfrage beim Binance-Spiegel (`ticker/price?symbols=[…]`), für
/// fehlende Coins einzeln der letzte Schlusskurs der 1-h-Kerzen aus der
/// `CandleDataSource`-Kette. USDT = 1. Zwischenspeicher 60 s.
///
/// Coin-Liste für die Suche: Binance-Spot-Paare gegen USDT (exchangeInfo), sonst
/// der Paar-Zwischenspeicher der Börse «Binance» (vom Aufrufer geliefert, da er
/// in der App liegt), sonst Coinbase-USD-Produkte. Zwischenspeicher 1 Tag
/// (auch über einen Neustart, in `SharedStorage.defaults`).
enum PortfolioPriceSource {
    static let stable = "USDT"
    private static let binanceHost = "data-api.binance.vision"
    private static let coinbaseHost = "api.exchange.coinbase.com"
    private static let maxSymbols = 100
    private static let priceTtlMillis: Int64 = 60_000
    private static let coinsTtlMillis: Int64 = 24 * 60 * 60_000
    private static let keyCoins = "portfolio_coin_list"
    private static let keyCoinsAt = "portfolio_coin_list_at"

    private static let lock = NSLock()
    /// Coin → (Kurs, Abfragezeit).
    nonisolated(unsafe) private static var cache: [String: (price: Double, at: Int64)] = [:]
    nonisolated(unsafe) private static var coinList: [String]?

    private static let priceGate = PortfolioSerialGate()
    private static let coinGate = PortfolioSerialGate()

    /// Gültiges Symbol: 1–15 Buchstaben oder Ziffern.
    static func isSymbol(_ s: String) -> Bool {
        (1...15).contains(s.count) && s.allSatisfy { $0.isLetter || $0.isNumber }
    }

    // MARK: Kurse

    /// Kurse aus dem Zwischenspeicher, ohne Netz (für den ersten Aufbau).
    static func cached(_ coins: [String]) -> PortfolioPrices { collect(normalized(coins)) }

    /// Kurse für `coins`; Einträge jünger als 60 s werden nicht neu geholt
    /// (ausser mit `force`). Schlägt eine Abfrage fehl, bleibt der letzte
    /// bekannte Kurs stehen.
    static func prices(_ coins: [String], force: Bool = false) async -> PortfolioPrices {
        let wanted = normalized(coins)
        await priceGate.run { () async -> Void in
            let now = TimeUtils.nowMillis
            let toFetch = wanted.filter { coin in force || !Self.isFresh(coin, now: now) }
            guard !toFetch.isEmpty else { return }
            var fetched = await Self.binanceBatch(toFetch)
            let missing = toFetch.filter { fetched[$0] == nil }
            if !missing.isEmpty {
                let results = await withTaskGroup(of: (String, Double?).self,
                                                  returning: [(String, Double?)].self) { group in
                    for coin in missing {
                        group.addTask {
                            let price = await Self.candlePrice(coin)
                            return (coin, price)
                        }
                    }
                    var out: [(String, Double?)] = []
                    for await r in group { out.append(r) }
                    return out
                }
                for (coin, price) in results {
                    if let price { fetched[coin] = price }
                }
            }
            Self.store(fetched, at: TimeUtils.nowMillis)
        }
        return collect(wanted)
    }

    /// Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt.
    static func price(_ coin: String) async -> Double? {
        let symbol = PortfolioCalculator.normalizeCoin(coin)
        if symbol == stable { return 1 }
        return await prices([symbol]).prices[symbol]
    }

    private static func normalized(_ coins: [String]) -> [String] {
        var seen = Set<String>()
        return coins.map { PortfolioCalculator.normalizeCoin($0) }
            .filter { isSymbol($0) && $0 != stable && seen.insert($0).inserted }
    }

    private static func isFresh(_ coin: String, now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let entry = cache[coin] else { return false }
        let age = now - entry.at
        return age >= 0 && age < priceTtlMillis
    }

    private static func store(_ fetched: [String: Double], at time: Int64) {
        lock.lock(); defer { lock.unlock() }
        for (coin, price) in fetched { cache[coin] = (price: price, at: time) }
    }

    private static func collect(_ coins: [String]) -> PortfolioPrices {
        lock.lock(); defer { lock.unlock() }
        var result: [String: Double] = [:]
        var oldest: Int64?
        for coin in coins {
            guard let entry = cache[coin] else { continue }
            result[coin] = entry.price
            oldest = min(oldest ?? entry.at, entry.at)
        }
        result[stable] = 1
        return PortfolioPrices(prices: result, updatedAt: oldest ?? 0)
    }

    private static func knownCoins() -> [String]? {
        lock.lock(); defer { lock.unlock() }
        return coinList
    }

    private static func setKnownCoins(_ list: [String]) {
        lock.lock(); defer { lock.unlock() }
        coinList = list
    }

    /// Sammelabfrage; Binance lehnt die ganze Anfrage ab, wenn ein Symbol unbekannt ist.
    private static func binanceBatch(_ coins: [String]) async -> [String: Double] {
        if BlockedSources.isBlocked(binanceHost) { return [:] }
        // Bekannte Coins zuerst — unbekannte gehen direkt über die Kerzen-Kette.
        let known = knownCoins().map { Set($0) }
        let candidates: [String]
        if let known, !known.isEmpty {
            candidates = coins.filter { known.contains($0) }
        } else {
            candidates = coins
        }
        var result: [String: Double] = [:]
        var start = 0
        while start < candidates.count {
            let chunk = Array(candidates[start..<min(start + maxSymbols, candidates.count)])
            start += maxSymbols
            var bySymbol: [String: String] = [:]
            for coin in chunk { bySymbol[coin + stable] = coin }
            let symbols = "[" + chunk.map { "\"\($0)\(stable)\"" }.joined(separator: ",") + "]"
            guard let encoded = symbols.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { continue }
            let url = "https://\(binanceHost)/api/v3/ticker/price?symbols=\(encoded)"
            guard let body = await get(url, host: binanceHost, session: MarketHTTP.session),
                  let data = body.data(using: .utf8),
                  let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
            else { continue }
            for item in array {
                guard let o = item as? [String: Any],
                      let symbol = o["symbol"] as? String,
                      let coin = bySymbol[symbol]
                else { continue }
                let price: Double?
                if let s = o["price"] as? String { price = Double(s) }
                else if let n = o["price"] as? NSNumber { price = n.doubleValue }
                else { price = nil }
                if let price, price > 0, price.isFinite { result[coin] = price }
            }
        }
        return result
    }

    private static func candlePrice(_ coin: String) async -> Double? {
        guard let close = await CandleDataSource.candles(base: coin, quote: stable, interval: .h1, limit: 2)?.last?.close,
              close > 0, close.isFinite
        else { return nil }
        return close
    }

    // MARK: Coin-Liste

    /// Alle wählbaren Coins (alphabetisch); leer, wenn keine Quelle antwortet.
    /// `fallback`: Paarliste der Börse «Binance» aus der App (nil = keine).
    static func coins(fallback: (@Sendable () async -> [String]?)? = nil) async -> [String] {
        await coinGate.run { await Self.loadCoins(fallback: fallback) }
    }

    private static func loadCoins(fallback: (@Sendable () async -> [String]?)?) async -> [String] {
        let defaults = SharedStorage.defaults
        let storedAt = Int64(defaults.double(forKey: keyCoinsAt))
        let age = TimeUtils.nowMillis - storedAt
        let fresh = age >= 0 && age < coinsTtlMillis
        if let list = knownCoins(), fresh { return list }

        let stored = (defaults.string(forKey: keyCoins) ?? "")
            .split(separator: ",").map(String.init).filter { !$0.isEmpty }
        if !stored.isEmpty && fresh {
            setKnownCoins(stored)
            return stored
        }

        var loaded = await binanceCoins()
        if loaded == nil, let fallback {
            let list = await fallback()
            if let list, !list.isEmpty { loaded = list }
        }
        if loaded == nil { loaded = await coinbaseCoins() }

        if let loaded, !loaded.isEmpty {
            let list = Array(Set(loaded + [stable])).sorted()
            defaults.set(list.joined(separator: ","), forKey: keyCoins)
            defaults.set(Double(TimeUtils.nowMillis), forKey: keyCoinsAt)
            setKnownCoins(list)
            return list
        }
        // Lieber eine ältere Liste als gar keine
        if !stored.isEmpty { setKnownCoins(stored) }
        return stored
    }

    private static func binanceCoins() async -> [String]? {
        let url = "https://\(binanceHost)/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false"
        guard let body = await get(url, host: binanceHost, session: MarketHTTP.bulkSession),
              let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let symbols = root["symbols"] as? [Any]
        else { return nil }
        let list: [String] = symbols.compactMap { item in
            guard let s = item as? [String: Any],
                  s["quoteAsset"] as? String == stable,
                  s["status"] as? String == "TRADING",
                  let base = s["baseAsset"] as? String
            else { return nil }
            let symbol = base.uppercased()
            return isSymbol(symbol) ? symbol : nil
        }
        return list.isEmpty ? nil : list
    }

    private static func coinbaseCoins() async -> [String]? {
        guard let body = await get("https://\(coinbaseHost)/products", host: coinbaseHost, session: MarketHTTP.bulkSession),
              let data = body.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return nil }
        let list: [String] = array.compactMap { item in
            guard let p = item as? [String: Any],
                  p["quote_currency"] as? String == "USD",
                  (p["trading_disabled"] as? Bool) != true,
                  let base = p["base_currency"] as? String
            else { return nil }
            let symbol = base.uppercased()
            return isSymbol(symbol) ? symbol : nil
        }
        return list.isEmpty ? nil : list
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    private static func get(_ url: String, host: String, session: URLSession) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }
}
