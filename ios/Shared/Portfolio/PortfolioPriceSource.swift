import Foundation

/// Kurse und Coin-Liste für das Portfolio, immer in USDT — wie `PortfolioPriceSource.kt`.
///
/// Kurse: Sammelabfrage beim Binance-Spiegel (`ticker/price?symbols=[…]`), für
/// fehlende Coins einzeln der letzte Schlusskurs der 1-h-Kerzen aus der
/// `CandleDataSource`-Kette. USDT = 1; andere Stablecoins (USDC, DAI …) werden wie jeder Coin
/// geholt — fehlt ihr Kurs, haben sie keinen Wert (`PortfolioStables`). Zwischenspeicher 60 s.
///
/// Identitätsprüfung des Kerzenkurses: Er gilt nur, wenn er zum selben Token passt wie ein
/// unabhängiger Bezug (`PricePlausibility.candleReference`: Merklisten-Kurs desselben Coins gegen
/// einen USD-Stablecoin, sonst der letzte gute gemerkte Kurs) — Verhältnis 1/3…3
/// (`PricePlausibility.sameToken`). Passt er nicht (gleiches Kürzel, anderer Token), gibt es keinen
/// neuen Kurs («—») statt einer sicher wirkenden falschen Summe. Gibt es gar keinen Bezug (noch nie
/// ein Kurs, keine passende Merkliste), zählt der Kerzenkurs NICHT als Geld (wie der Stichtag-Export):
/// kein Kurs, der Coin heisst «ungeprüft» (`PortfolioPrices.unchecked`). Vor der Kerze gilt der Kurs
/// eines Merklisten-Paars desselben Coins an einer anderen Spot-Börse (`PortfolioWatchPair`).
///
/// Coin-Liste für die Suche: Binance-Spot-Paare gegen USDT (exchangeInfo), sonst
/// der Paar-Zwischenspeicher der Börse «Binance» (vom Aufrufer geliefert, da er
/// in der App liegt), sonst Coinbase-USD-Produkte. Zwischenspeicher 1 Tag
/// (auch über einen Neustart, in `SharedStorage.defaults`).
///
/// Hier Zustand und Abruf der Kurse; gemerkter Stand in `PortfolioPriceSource+Store.swift`, Coin-Liste in
/// `PortfolioPriceSource+Coins.swift`, Ergebnis-Typen in `PortfolioPrices.swift` (wie Android).
enum PortfolioPriceSource {
    static let stable = "USDT"
    static let binanceHost = "data-api.binance.vision"
    static let coinbaseHost = "api.exchange.coinbase.com"
    private static let maxSymbols = 100
    private static let priceTtlMillis: Int64 = 60_000
    static let coinsTtlMillis: Int64 = 24 * 60 * 60_000
    static let keyCoins = "portfolio_coin_list"
    static let keyCoinsAt = "portfolio_coin_list_at"

    static let lock = NSLock()
    /// Coin → (Kurs, Abfragezeit).
    nonisolated(unsafe) static var cache: [String: (price: Double, at: Int64)] = [:]
    /// Coins ohne Kurs, weil nur ein ungeprüfter Kerzenkurs da war (kein Bezug vorhanden).
    nonisolated(unsafe) static var uncheckedCoins: Set<String> = []
    /// Coins ohne Kurs, weil die letzte Abfrage scheiterte (`PortfolioPriceIssue.unavailable`).
    nonisolated(unsafe) static var unavailableCoins: Set<String> = []
    /// Quellen der Kerzen-Kette (`CandleDataSource`) — Binance, Binance.US, Coinbase.
    private static let candleHosts = ["data-api.binance.vision", "api.binance.com", "api.binance.us", "api.exchange.coinbase.com"]
    nonisolated(unsafe) static var coinList: [String]?
    /// Coin → Börse, deren Kurs im Zwischenspeicher steht; Coin → eingetragene Börse ohne Kurs; Binance-Bezug.
    nonisolated(unsafe) static var sourceOf: [String: String] = [:]
    nonisolated(unsafe) static var failedOf: [String: String] = [:]
    nonisolated(unsafe) static var binanceOf: [String: Double] = [:]
    nonisolated(unsafe) static var mixedCoins: Set<String> = []

    private static let priceGate = PortfolioSerialGate()
    static let coinGate = PortfolioSerialGate()

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
            // Dazu die Basis gekapselter Coins (Ersatzkurs, `ChainAssets.priceViaBase`); nur abfragbare Kürzel
            let toFetch = ChainAssets.withBases(wanted).filter { coin in
                Self.isSymbol(coin) && coin != Self.stable && (force || !Self.isFresh(coin, now: now))
            }
            guard !toFetch.isEmpty else { return }
            // Binance-Ticker nur für denselben Token (Namen der Merkliste, K-8); sonst Merklisten-Paar/Kerzen
            let identity = TokenIdentityStore.shared
            let watchNames = PortfolioPriceIdentity.watchNames(
                toFetch,
                watches: SharedStorage.loadSnapshot().watches.map {
                    PortfolioPriceIdentity.WatchName(base: $0.baseAsset,
                                                     name: identity.pairName(marketKey: $0.marketKey, base: $0.baseAsset),
                                                     disabled: $0.disabled)
                })
            let binanceCoins = PortfolioPriceIdentity.binanceTickerCoins(toFetch, watchNames: watchNames,
                                                                         binanceName: { identity.binanceName($0) })
            let batch = await Self.binanceBatch(binanceCoins)
            var fetched = batch.prices
            // Nicht bei Binance: zuerst ein Merklisten-Paar an einer anderen Börse, dann die Kerze
            let notListed = toFetch.filter { fetched[$0] == nil }
            if !notListed.isEmpty {
                for (coin, price) in await Self.watchPairPrices(notListed) { fetched[coin] = price }
            }
            let missing = toFetch.filter { fetched[$0] == nil }
            var unchecked = Set<String>()
            var rejected = Set<String>()
            var unavailable = Set<String>()
            if !missing.isEmpty {
                // Gekapselter Coin ohne eigenen Bezug: der Kurs der Basis prüft seinen Kerzenkurs (WETH ≈ ETH)
                var references = Self.candleReferences(missing)
                for coin in missing where references[coin] == nil {
                    guard let base = ChainAssets.wrappedBase(coin) else { continue }
                    if let ref = fetched[base] ?? Self.cachedPrice(base) { references[coin] = ref }
                }
                let watches = SharedStorage.loadSnapshot().watches
                let identityNames = missing.map { TokenIdentityStore.shared.watchName($0, watches: watches) }
                let results = await withTaskGroup(of: (String, Double?).self,
                                                  returning: [(String, Double?)].self) { group in
                    for (coin, identityName) in zip(missing, identityNames) {
                        group.addTask {
                            let price = await Self.candlePrice(coin, identityName: identityName)
                            return (coin, price)
                        }
                    }
                    var out: [(String, Double?)] = []
                    for await r in group { out.append(r) }
                    return out
                }
                let sourcesBlocked = Self.candleHosts.allSatisfy { BlockedSources.isBlocked($0) }
                for (coin, price) in results {
                    // Kein Kurs: Fehler (Netz/Sperre) von «gibt es nicht» unterscheiden
                    if price == nil, PortfolioPriceIssue.of(failed: false, allSourcesBlocked: sourcesBlocked,
                                                            networkFailed: batch.failed) == .unavailable {
                        unavailable.insert(coin)
                    }
                    let reference = references[coin]
                    if let price, PricePlausibility.acceptCandle(price, reference: reference) {
                        fetched[coin] = price
                    } else if PricePlausibility.candleUnchecked(price, reference: reference) {
                        // Kein Bezug: ungeprüft — zählt nicht als Geld (kein Kurs, «ungeprüft»)
                        unchecked.insert(coin)
                    } else if price != nil {
                        // Gleiches Kürzel, anderer Token: kein neuer Kurs statt einer falschen Summe
                        rejected.insert(coin)
                    }
                }
            }
            // Eingetragene Börse: deren Kurs gilt, sonst bleibt Binance (PortfolioExchangeRoute)
            fetched = await Self.applyRoutes(toFetch, fetched)
            Self.store(fetched, at: TimeUtils.nowMillis, unchecked: unchecked, rejected: rejected,
                       requested: toFetch, unavailable: unavailable)
            // Letzten guten Stand merken (für ohne Netz und nach einem Neustart)
            if !fetched.isEmpty { Self.persist() }
        }
        return collect(wanted)
    }

    // MARK: Kurs der eingetragenen Börse

    /// Wege aus der App Group (`PortfolioExchangeRouteStore`, von der App aufgelöst): Kurs über den Adapter der
    /// Börse, in USDT umgerechnet; ersetzt den Binance-Kurs. Scheitert es, bleibt Binance (`failedOf`).
    /// Abfragen gehen nur an die Börse, die der Nutzer selbst eingetragen hat — wie Android.
    private static func applyRoutes(_ coins: [String], _ binancePrices: [String: Double]) async -> [String: Double] {
        let stored = PortfolioExchangeRouteStore.load()
        var fetched = binancePrices
        var sources: [String: String] = [:]
        var failed: [String: String] = [:]
        var refs: [String: Double] = [:]
        for coin in coins where coin != stable {
            if let name = stored.unresolved[coin] { failed[coin] = name; continue }
            guard let route = stored.routes[coin], let market = MarketsConfig.market(route.marketKey) else { continue }
            let info = MarketPair(route.base, route.quote, route.pairId)
            var price: Double? = nil
            if let ticker = try? await MarketService.fetchTicker(market: market, info: info) {
                let quote = route.quote.uppercased()
                let rate: Double? = (quote == "USDT" || quote == "USD") ? nil : await FxRateSource.usdTo(quote)
                // USDT zum Marktkurs: USD-/Fiat-Kurs durch USDT/USD (ohne frische Messung 1, Karte markiert die Näherung)
                price = PortfolioExchangeRoute.toUsdt(ticker.last, quote: quote,
                                                      usdtUsd: DepegAlarmCheck.liveUsdtUsd() ?? 1) { _ in rate }
            }
            if let price {
                if let ref = fetched[coin] { refs[coin] = ref }
                fetched[coin] = price
                sources[coin] = route.marketName
            } else {
                failed[coin] = route.marketName
            }
        }
        lock.lock()
        for coin in coins {
            sourceOf[coin] = sources[coin]
            failedOf[coin] = failed[coin]
            binanceOf[coin] = refs[coin]
        }
        mixedCoins = stored.mixed
        lock.unlock()
        return fetched
    }

    /// Zusatzfelder (Börse, Hinweise) für die gefundenen Coins; nur mit gehaltenem `lock` aufrufen.
    static func decorateLocked(_ p: inout PortfolioPrices, coins: [String]) {
        for coin in coins where p.prices[coin] != nil {
            if let s = sourceOf[coin] { p.sources[coin] = s }
            if let f = failedOf[coin] { p.exchangeFailed[coin] = f }
            if let b = binanceOf[coin] { p.binance[coin] = b }
            if mixedCoins.contains(coin) { p.mixedExchange.insert(coin) }
        }
    }

    // MARK: Letzter Stand auf dem Gerät

    /// Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt.
    static func price(_ coin: String) async -> Double? {
        let symbol = PortfolioCalculator.normalizeCoin(coin)
        if !PortfolioStables.needsQuote(symbol) { return 1 }
        // Stablecoin ohne Marktkurs: kein Wert (gleiche Regel wie Kopf, Verlauf und Widget)
        let market = await prices([symbol]).prices[symbol]
        return PortfolioStables.price(symbol, market: market)
    }

    /// Abfragbare Kürzel; gekapselte Coins (`ChainAssets.wrapped`, z. B. «BTC.B») auch ohne — Kurs dann über die Basis.
    static func normalized(_ coins: [String]) -> [String] {
        var seen = Set<String>()
        return coins.map { PortfolioCalculator.normalizeCoin($0) }
            .filter { (isSymbol($0) || ChainAssets.wrappedBase($0) != nil) && $0 != stable && seen.insert($0).inserted }
    }

    /// Kurs im Zwischenspeicher (ohne Altersgrenze); nil = keiner.
    private static func cachedPrice(_ coin: String) -> Double? {
        lock.lock(); defer { lock.unlock() }
        return cache[coin]?.price
    }

    private static func isFresh(_ coin: String, now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let entry = cache[coin] else { return false }
        let age = now - entry.at
        return age >= 0 && age < priceTtlMillis
    }

    /// Neue Kurse in den Zwischenspeicher; `unchecked` (nur ungeprüfte Kerze, sonst kein Kurs): kein Wert,
    /// Coin «ungeprüft»; `rejected` (Kerze passt nicht zum Bezug): nicht mehr «ungeprüft».
    /// `unavailable` (Abfrage gescheitert): unter den angefragten Coins ohne Kurs «nicht abrufbar», die übrigen nicht mehr.
    private static func store(_ fetched: [String: Double], at time: Int64, unchecked: Set<String>, rejected: Set<String>,
                              requested: [String] = [], unavailable: Set<String> = []) {
        lock.lock(); defer { lock.unlock() }
        for (coin, price) in fetched {
            cache[coin] = (price: price, at: time)
            uncheckedCoins.remove(coin)
            unavailableCoins.remove(coin)
        }
        for coin in requested where fetched[coin] == nil {
            if unavailable.contains(coin) { unavailableCoins.insert(coin) } else { unavailableCoins.remove(coin) }
        }
        for coin in rejected { uncheckedCoins.remove(coin) }
        for coin in unchecked where fetched[coin] == nil {
            cache[coin] = nil
            uncheckedCoins.insert(coin)
        }
    }

    /// Kurse aus Merklisten-Paaren für Coins, die Binance-Spot nicht führt (`PortfolioWatchPair`): aktives
    /// Spot-Paar desselben Coins an einer anderen Börse, Kurs über deren Adapter, in USDT umgerechnet.
    /// Abfragen gehen nur an Börsen, deren Paar der Nutzer selbst in der Merkliste hat — wie Android.
    private static func watchPairPrices(_ coins: [String]) async -> [String: Double] {
        let watches = SharedStorage.loadSnapshot().watches
        guard !watches.isEmpty else { return [:] }
        let names = TokenIdentityStore.shared
        let candidates = watches.map { w in
            PortfolioWatchPair.Candidate(marketKey: w.marketKey, base: w.baseAsset, quote: w.quoteAsset, pairId: w.pairId,
                                         spot: w.contractType == .none, disabled: w.disabled, lastUpdate: w.lastUpdate,
                                         // Voller Name aus der Paarliste der Börse (gleicher Token, K-8)
                                         name: names.pairName(marketKey: w.marketKey, base: w.baseAsset))
        }
        var picks: [(String, PortfolioWatchPair.Candidate)] = []
        for coin in coins {
            if let pick = PortfolioWatchPair.pick(coin, watches: candidates) { picks.append((coin, pick)) }
        }
        guard !picks.isEmpty else { return [:] }
        return await withTaskGroup(of: (String, Double?).self, returning: [String: Double].self) { group in
            for (coin, pick) in picks {
                group.addTask { (coin, await Self.watchPairPrice(pick)) }
            }
            var out: [String: Double] = [:]
            for await (coin, price) in group { if let price { out[coin] = price } }
            return out
        }
    }

    /// Letzter Kurs des Merklisten-Paars `pick` in USDT; nil = Börse unbekannt, keine Antwort oder kein Devisenkurs.
    private static func watchPairPrice(_ pick: PortfolioWatchPair.Candidate) async -> Double? {
        guard let market = MarketsConfig.market(pick.marketKey) else { return nil }
        let info = MarketPair(pick.base, pick.quote, pick.pairId)
        guard let ticker = try? await MarketService.fetchTicker(market: market, info: info) else { return nil }
        let quote = pick.quote.uppercased()
        let rate: Double? = (quote == "USDT" || quote == "USD") ? nil : await FxRateSource.usdTo(quote)
        return PortfolioExchangeRoute.toUsdt(ticker.last, quote: quote,
                                             usdtUsd: DepegAlarmCheck.liveUsdtUsd() ?? 1) { _ in rate }
    }

    /// Unabhängige Bezüge für Kerzenkurse (`PricePlausibility.candleReference`): Kurse aktiver
    /// Spot-Paare der Merkliste mit demselben Coin gegen einen USD-Stablecoin (höchstens
    /// `PortfolioPriceFallback.maxAgeMillis` alt), sonst der letzte gute Kurs (Zwischenspeicher ohne
    /// ungeprüfte Kerzenkurse, dazu der gemerkte Stand). Coins ohne Bezug fehlen.
    private static func candleReferences(_ coins: [String]) -> [String: Double] {
        let stored = loadStored()
        lock.lock()
        var checked = currentEntriesLocked()
        for coin in uncheckedCoins { checked[coin] = nil }
        lock.unlock()
        let merged = PortfolioPriceFallback.merge(current: checked, stored: stored)
        let watches = SharedStorage.loadSnapshot().watches
        let now = TimeUtils.nowMillis
        var out: [String: Double] = [:]
        for coin in coins {
            let watchPrices = watches.compactMap { referencePrice($0, coin: coin, now: now) }
            if let reference = PricePlausibility.candleReference(watchPrices: watchPrices, stored: merged[coin]?.price) {
                out[coin] = reference
            }
        }
        return out
    }

    /// Kurs eines Merklisten-Paars als Bezug für `coin`: aktiv, Spot, gleicher Coin gegen einen
    /// USD-Stablecoin, nicht älter als `PortfolioPriceFallback.maxAgeMillis`; sonst nil.
    private static func referencePrice(_ w: Watch, coin: String, now: Int64) -> Double? {
        guard !w.disabled, w.contractType == .none,
              PortfolioCalculator.normalizeCoin(w.baseAsset) == coin,
              CurrencyConversion.usdStables.contains(PortfolioCalculator.normalizeCoin(w.quoteAsset))
        else { return nil }
        let age = now - w.lastUpdate
        guard age >= 0, age <= PortfolioPriceFallback.maxAgeMillis else { return nil }
        return w.lastPrice
    }

    private static func collect(_ coins: [String]) -> PortfolioPrices {
        lock.lock(); defer { lock.unlock() }
        var result: [String: Double] = [:]
        var times: [String: Int64] = [:]
        var oldest: Int64?
        var unchecked = Set<String>()
        var unavailable = Set<String>()
        // Gekapselte Coins ohne eigenen Kurs: Kurs (und Zeit) der Basis, markiert (`PortfolioPrices.viaBase`)
        let via = ChainAssets.priceViaBase(coins, available: Set(cache.keys))
        for coin in coins {
            guard let entry = cache[coin] ?? via[coin].flatMap({ cache[$0] }) else {
                // Ungeprüft = ohne Kurs (zählt nicht als Geld); Abfrage gescheitert: «Kurs gerade nicht abrufbar»
                if uncheckedCoins.contains(coin) { unchecked.insert(coin) }
                else if unavailableCoins.contains(coin) { unavailable.insert(coin) }
                continue
            }
            result[coin] = entry.price
            times[coin] = entry.at
            oldest = min(oldest ?? entry.at, entry.at)
        }
        result[stable] = 1
        var out = PortfolioPrices(prices: result, updatedAt: oldest ?? 0, unchecked: unchecked, times: times)
        out.unavailable = unavailable
        out.viaBase = via
        decorateLocked(&out, coins: coins)
        return out
    }

    /// Sammelabfrage; Binance lehnt die ganze Anfrage ab, wenn ein Symbol unbekannt ist. `failed`: eine Abfrage scheiterte
    /// am Netz bzw. die Quelle ist gesperrt (für `PortfolioPriceIssue`).
    private static func binanceBatch(_ coins: [String]) async -> (prices: [String: Double], failed: Bool) {
        if BlockedSources.isBlocked(binanceHost) { return ([:], true) }
        var failed = false
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
            guard let body = await get(url, host: binanceHost, session: MarketHTTP.session) else {
                failed = true
                continue
            }
            guard let data = body.data(using: .utf8),
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
        return (result, failed)
    }

    private static func candlePrice(_ coin: String, identityName: String?) async -> Double? {
        // Binance nur ohne klaren Namens-Widerspruch zu den Paaren der Merkliste (gleicher Token, K-8)
        guard let close = await CandleDataSource.candles(base: coin, quote: stable, interval: .h1, limit: 2,
                                                         identityName: identityName)?.last?.close,
              close > 0, close.isFinite
        else { return nil }
        return close
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    static func get(_ url: String, host: String, session: URLSession) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }
}
