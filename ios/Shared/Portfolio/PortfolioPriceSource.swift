import Foundation
import os

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
    private static let priceTtlMillis: Int64 = 60_000
    static let coinsTtlMillis: Int64 = 24 * 60 * 60_000
    static let keyCoins = "portfolio_coin_list"
    static let keyCoinsAt = "portfolio_coin_list_at"

    /// Zwischenspeicher der Kurse (auch für die Erweiterungen) — alles hinter einer Sperre (`state`). Nicht privat nur
    /// für die Tests.
    struct CacheState: Sendable {
        /// Coin → (Kurs, Abfragezeit).
        var cache: [String: (price: Double, at: Int64)] = [:]
        /// Coins ohne Kurs, weil nur ein ungeprüfter Kerzenkurs da war (kein Bezug vorhanden).
        var uncheckedCoins: Set<String> = []
        /// Coins mit Binance-Kurs, dessen Token nicht bestätigt ist (`PortfolioPrices.unverified`).
        var unverifiedCoins: Set<String> = []
        /// Coins ohne Kurs, weil die letzte Abfrage scheiterte (`PortfolioPriceIssue.unavailable`).
        var unavailableCoins: Set<String> = []
        /// Coins ohne Binance-Namen, für die keine Quelle einen Kurs führt (`PortfolioPrices.noMarket`).
        var noMarketCoins: Set<String> = []
        /// Coin → Zeitpunkt, an dem Binance ihn einzeln mit 400 ablehnte (`binanceBatch`, nur im Arbeitsspeicher).
        var binanceUnlisted: [String: Int64] = [:]
        var coinList: [String]?
        /// Coin → Börse, deren Kurs im Zwischenspeicher steht; Coin → eingetragene Börse ohne Kurs; Binance-Bezug.
        var sourceOf: [String: String] = [:]
        var failedOf: [String: String] = [:]
        var binanceOf: [String: Double] = [:]
        var mixedCoins: Set<String> = []
        /// Gemerkte Kurse (`PortfolioPriceSource+Store.swift`); nil = Datei noch nicht gelesen.
        var storedEntries: [String: StoredPrice]?
        /// Zähler von «Portfolio leeren» (`clearStored`): eine Abfrage, die davor begann, übernimmt ihre Kurse nicht mehr
        /// und schreibt die Datei nicht neu (`persist`) — sonst kämen die gelöschten Coins zurück. Wie Android.
        var clearGeneration = 0

        /// Zwischenspeicher als gemerkte Kurse.
        func currentEntries() -> [String: StoredPrice] {
            var out: [String: StoredPrice] = [:]
            // «Kurs ungeprüft» wird mitgemerkt (`StoredPrice.unverified`)
            for (coin, entry) in cache {
                out[coin] = StoredPrice(price: entry.price, at: entry.at, unverified: unverifiedCoins.contains(coin))
            }
            return out
        }

        /// Zusatzfelder (Börse, Hinweise) für die gefundenen Coins.
        func decorate(_ p: inout PortfolioPrices, coins: [String]) {
            for coin in coins where p.prices[coin] != nil {
                if let s = sourceOf[coin] { p.sources[coin] = s }
                if let f = failedOf[coin] { p.exchangeFailed[coin] = f }
                if let b = binanceOf[coin] { p.binance[coin] = b }
                if mixedCoins.contains(coin) { p.mixedExchange.insert(coin) }
            }
        }
    }

    static let state = OSAllocatedUnfairLock(initialState: CacheState())
    /// Quellen der Kerzen-Kette (`CandleDataSource`) — Binance, Binance.US, Coinbase.
    private static let candleHosts = ["data-api.binance.vision", "api.binance.com", "api.binance.us", "api.exchange.coinbase.com"]

    private static let priceGate = PortfolioSerialGate()
    static let coinGate = PortfolioSerialGate()
    /// Höchstens so viele Kerzen-Abfragen zugleich für Coins ohne Binance-Kurs (wie `HistoricPriceSource.parallel`).
    private static let candleParallel = 4

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
            let generation = Self.state.withLock { $0.clearGeneration }
            let now = EpochTime.nowMillis
            // Dazu die Basis gekapselter Coins (Ersatzkurs, `ChainAssets.priceViaBase`); nur abfragbare Kürzel
            let toFetch = ChainAssets.withBases(wanted).filter { coin in
                Self.isSymbol(coin) && coin != Self.stable && (force || !Self.isFresh(coin, now: now))
            }
            guard !toFetch.isEmpty else { return }
            // Binance-Ticker nur für denselben Token (Namen der Merkliste); ohne Namen nur bei eindeutigem
            // Kürzel, sonst Merklisten-Paar/Kerzen und ohne anderen Kurs «ungeprüft»; mehrdeutig laut anderen
            // Paarlisten: Binance-Kurs, aber «Kurs ungeprüft»
            let identity = TokenIdentityStore.shared
            let watchNames = PortfolioPriceIdentity.watchNames(
                toFetch,
                watches: SharedStorage.loadSnapshot().watches.map {
                    PortfolioPriceIdentity.WatchName(base: $0.baseAsset,
                                                     name: identity.pairName(exchangeKey: $0.exchangeKey, base: $0.baseAsset),
                                                     disabled: $0.disabled)
                })
            let split = PortfolioPriceIdentity.binanceSplit(toFetch, knownNames: watchNames,
                                                            binanceName: { identity.binanceName($0) },
                                                            otherNames: { identity.otherNames($0) })
            let batch = await Self.binanceBatch(split.use)
            var fetched = batch.prices
            let binanceUnverified = split.unverified.intersection(batch.prices.keys)
            // Nicht bei Binance: zuerst ein Merklisten-Paar an einer anderen Börse, dann die Kerze
            let notListed = toFetch.filter { fetched[$0] == nil }
            if !notListed.isEmpty {
                for (coin, price) in await Self.watchPairPrices(notListed) { fetched[coin] = price }
            }
            let missing = toFetch.filter { fetched[$0] == nil }
            var unchecked = Set<String>()
            var rejected = Set<String>()
            var unavailable = Set<String>()
            var noPrice = Set<String>()
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
                    // Höchstens `candleParallel` zugleich, auch bei vielen Coins (wie Android)
                    var pending = zip(missing, identityNames).makeIterator()
                    for _ in 0..<Self.candleParallel {
                        guard let next = pending.next() else { break }
                        let (coin, identityName) = next
                        group.addTask { (coin, await Self.candlePrice(coin, identityName: identityName)) }
                    }
                    var out: [(String, Double?)] = []
                    for await r in group {
                        out.append(r)
                        guard let next = pending.next() else { continue }
                        let (coin, identityName) = next
                        group.addTask { (coin, await Self.candlePrice(coin, identityName: identityName)) }
                    }
                    return out
                }
                let sourcesBlocked = Self.candleHosts.allSatisfy { BlockedSources.isBlocked($0) }
                for (coin, price) in results {
                    // Kein Kurs: Fehler (Netz/Sperre) von «gibt es nicht» unterscheiden
                    if price == nil {
                        let issue = PortfolioPriceIssue.of(failed: false, allSourcesBlocked: sourcesBlocked,
                                                           networkFailed: batch.failed)
                        if issue == .unavailable { unavailable.insert(coin) } else { noPrice.insert(coin) }
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
            // Binance ohne prüfbare Identität ausgelassen und kein anderer Kurs: «ungeprüft» (nicht abgelehnte); lieferte auch
            // keine Quelle einen Kurs (nicht gescheitert): «kein Markt»
            let skipped = PortfolioPriceIssue.nameUnchecked(split.unchecked, rejected: rejected, noPrice: noPrice)
            unchecked.formUnion(skipped.unchecked)
            // Eingetragene Börse: deren Kurs gilt, sonst bleibt Binance (PortfolioExchangeRoute)
            fetched = await Self.applyRoutes(toFetch, fetched, generation: generation)
            // «Portfolio leeren» lief während der Abfrage: nichts übernehmen, keine Datei neu anlegen
            let kept = Self.store(fetched, at: EpochTime.nowMillis, unchecked: unchecked, rejected: rejected,
                                  requested: toFetch, unavailable: unavailable, unverified: binanceUnverified,
                                  noMarket: skipped.noMarket, generation: generation)
            // Letzten guten Stand merken (für ohne Netz und nach einem Neustart)
            if kept && !fetched.isEmpty { Self.persist(generation: generation) }
        }
        return collect(wanted)
    }

    // MARK: Kurs der eingetragenen Börse

    /// Wege aus der App Group (`PortfolioExchangeRouteStore`, von der App aufgelöst): Kurs über den Adapter der
    /// Börse, in USDT umgerechnet; ersetzt den Binance-Kurs. Scheitert es, bleibt Binance (`failedOf`).
    /// Abfragen gehen nur an die Börse, die der Nutzer selbst eingetragen hat — wie Android.
    private static func applyRoutes(_ coins: [String], _ binancePrices: [String: Double],
                                    generation: Int) async -> [String: Double] {
        let stored = PortfolioExchangeRouteStore.load()
        var fetched = binancePrices
        var sources: [String: String] = [:]
        var failed: [String: String] = [:]
        var refs: [String: Double] = [:]
        for coin in coins where coin != stable {
            if let name = stored.unresolved[coin] { failed[coin] = name; continue }
            guard let route = stored.routes[coin], let exchange = ExchangesConfig.exchange(route.exchangeKey) else { continue }
            let info = ExchangePair(route.base, route.quote, route.pairId)
            var price: Double? = nil
            if let ticker = try? await ExchangeService.fetchTicker(exchange: exchange, info: info) {
                let quote = route.quote.uppercased()
                let rate: Double? = (quote == "USDT" || quote == "USD") ? nil : await FxRateSource.usdTo(quote)
                // USDT zum Marktkurs: USD-/Fiat-Kurs durch USDT/USD (ohne frische Messung 1, Karte markiert die Näherung)
                price = PortfolioExchangeRoute.toUsdt(ticker.last, quote: quote,
                                                      usdtUsd: DepegAlarmCheck.liveUsdtUsd() ?? 1) { _ in rate }
            }
            if let price {
                if let ref = fetched[coin] { refs[coin] = ref }
                fetched[coin] = price
                sources[coin] = route.exchangeName
            } else {
                failed[coin] = route.exchangeName
            }
        }
        state.withLock { [sources, failed, refs, mixed = stored.mixed] s in
            // Inzwischen geleert: die Herkunft gelöschter Coins nicht wieder eintragen
            guard s.clearGeneration == generation else { return }
            for coin in coins {
                s.sourceOf[coin] = sources[coin]
                s.failedOf[coin] = failed[coin]
                s.binanceOf[coin] = refs[coin]
            }
            s.mixedCoins = mixed
        }
        return fetched
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
        state.withLock { $0.cache[coin]?.price }
    }

    private static func isFresh(_ coin: String, now: Int64) -> Bool {
        state.withLock { s in
            guard let entry = s.cache[coin] else { return false }
            let age = now - entry.at
            return age >= 0 && age < priceTtlMillis
        }
    }

    /// Neue Kurse in den Zwischenspeicher; `unchecked` (nur ungeprüfte Kerze, sonst kein Kurs): kein Wert,
    /// Coin «ungeprüft»; `rejected` (Kerze passt nicht zum Bezug): nicht mehr «ungeprüft».
    /// `unavailable` (Abfrage gescheitert): unter den angefragten Coins ohne Kurs «nicht abrufbar», die übrigen nicht mehr.
    /// `unverified` (Binance-Kurs mit unbestätigtem Token): «Kurs ungeprüft», solange keine eingetragene Börse gilt.
    /// false = seit `generation` geleert (`clearStored`): nichts übernommen.
    private static func store(_ fetched: [String: Double], at time: Int64, unchecked: Set<String>, rejected: Set<String>,
                              requested: [String] = [], unavailable: Set<String> = [], unverified: Set<String> = [],
                              noMarket: Set<String> = [], generation: Int) -> Bool {
        state.withLock { s in
            guard s.clearGeneration == generation else { return false }
            for (coin, price) in fetched {
                s.cache[coin] = (price: price, at: time)
                s.uncheckedCoins.remove(coin)
                s.unavailableCoins.remove(coin)
                s.noMarketCoins.remove(coin)
                // Ungeprüft nur, solange der Kurs von Binance stammt (eine eingetragene Börse nennt ihren Token)
                if unverified.contains(coin) && s.sourceOf[coin] == nil {
                    s.unverifiedCoins.insert(coin)
                } else {
                    s.unverifiedCoins.remove(coin)
                }
            }
            for coin in requested where fetched[coin] == nil {
                if unavailable.contains(coin) { s.unavailableCoins.insert(coin) } else { s.unavailableCoins.remove(coin) }
                if !noMarket.contains(coin) { s.noMarketCoins.remove(coin) }
            }
            for coin in rejected { s.uncheckedCoins.remove(coin) }
            for coin in unchecked where fetched[coin] == nil {
                s.cache[coin] = nil
                s.uncheckedCoins.insert(coin)
            }
            // Kein Binance-Name und keine Quelle mit Kurs: kein Wert, Coin «kein Markt» (zählt nicht als Geld)
            for coin in noMarket where fetched[coin] == nil {
                s.cache[coin] = nil
                s.uncheckedCoins.remove(coin)
                s.noMarketCoins.insert(coin)
            }
            return true
        }
    }

    /// Unabhängige Bezüge für Kerzenkurse (`PricePlausibility.candleReference`): Kurse aktiver
    /// Spot-Paare der Merkliste mit demselben Coin gegen einen USD-Stablecoin (höchstens
    /// `PortfolioPriceFallback.maxAgeMillis` alt), sonst der letzte gute Kurs (Zwischenspeicher ohne
    /// ungeprüfte Kerzenkurse, dazu der gemerkte Stand). Coins ohne Bezug fehlen.
    private static func candleReferences(_ coins: [String]) -> [String: Double] {
        let stored = loadStored()
        let checked: [String: StoredPrice] = state.withLock { s in
            var current = s.currentEntries()
            // Auch kein Binance-Kurs mit ungeprüftem Token als Bezug (`PortfolioPrices.unverified`)
            for coin in s.uncheckedCoins.union(s.unverifiedCoins) { current[coin] = nil }
            return current
        }
        // Ein gemerkter Kurs mit «Kurs ungeprüft» ist ebenso kein Bezug (`PortfolioPriceFallback.confirmed`)
        let merged = PortfolioPriceFallback.confirmed(PortfolioPriceFallback.merge(current: checked, stored: stored))
        let watches = SharedStorage.loadSnapshot().watches
        let now = EpochTime.nowMillis
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
        state.withLock { s in
            var result: [String: Double] = [:]
            var times: [String: Int64] = [:]
            var oldest: Int64?
            var unchecked = Set<String>()
            var unavailable = Set<String>()
            var noMarket = Set<String>()
            // Gekapselte Coins ohne eigenen Kurs: Kurs (und Zeit) der Basis, markiert (`PortfolioPrices.viaBase`)
            let via = ChainAssets.priceViaBase(coins, available: Set(s.cache.keys))
            for coin in coins {
                guard let entry = s.cache[coin] ?? via[coin].flatMap({ s.cache[$0] }) else {
                    // Ungeprüft = ohne Kurs (zählt nicht als Geld); Abfrage gescheitert: «Kurs gerade nicht abrufbar»
                    if s.uncheckedCoins.contains(coin) { unchecked.insert(coin) }
                    else if s.noMarketCoins.contains(coin) { noMarket.insert(coin) }
                    else if s.unavailableCoins.contains(coin) { unavailable.insert(coin) }
                    continue
                }
                result[coin] = entry.price
                times[coin] = entry.at
                oldest = min(oldest ?? entry.at, entry.at)
            }
            result[stable] = 1
            var out = PortfolioPrices(prices: result, updatedAt: oldest ?? 0, unchecked: unchecked, times: times)
            out.unavailable = unavailable
            out.noMarket = noMarket
            out.viaBase = via
            s.decorate(&out, coins: coins)
            // «Kurs ungeprüft»; gekapselte Coins über die Basis erben ihn (wie Android)
            out.unverified = PortfolioPriceFallback.unverified(coins, entries: s.currentEntries(), via: via)
            return out
        }
    }
}
