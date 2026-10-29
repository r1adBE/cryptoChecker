import Foundation
import os

// Ausweich-Kette des Wertverlaufs und USDT/USD-Tagesschlüsse (Teil von `PortfolioHistorySource`): Coins, die Binance
// nicht führt (oder bei nicht erreichbarem Binance) und ohne eingetragene Börse, bekommen Tageskerzen von Coinbase →
// Kraken → OKX → Bybit — nur Börsen, die die App ohnehin anfragt, über deren eigene Kerzen. Wie
// PortfolioHistorySourceChain.kt und `usdtUsdDaily` (Android).
extension PortfolioHistorySource {
    /// USDT/USD-Tagesschlüsse: Kraken USDTZUSD (dasselbe Paar wie die Messung des Depeg-Alarms).
    static let usdtUsdKey = "USDTZUSD"
    static let usdtUsdPair = CandlePair(exchangeKey: DepegAlarm.krakenKey, exchangeName: DepegAlarm.krakenKey,
                                        base: "USDT", quote: "USD", pairId: DepegAlarm.krakenPairId)
    /// Lückenfüller USDT/USD: Coinbase USDT-USD (eigene Tageskerzen, etwa 600 Tage).
    static let usdtUsdFillKey = "USDTUSDCB"
    static let usdtUsdFillDays = 600
    static let usdtUsdFillPair = CandlePair(exchangeKey: HistoricCloseChain.coinbase, exchangeName: HistoricCloseChain.coinbase,
                                            base: "USDT", quote: "USD", pairId: "USDT-USD")

    /// Tagesschlüsse USDT/USD (UTC-Tag → Schluss) für mindestens die letzten `days` Tage: Kraken-Tageskerzen USDTZUSD
    /// (höchstens 720 Tage). Fehlen dort Tage (nicht erreichbar, Lücke), füllt Coinbase USDT-USD sie
    /// (`HistoricCloseChain.fillUsdtUsd`); erst danach gilt 1 als Näherung (`UsdtUsdRates.on`). Speicher, Gerät (12 h),
    /// sonst Abfrage; nicht erreichbar → älterer Stand oder leer. Wie Android `usdtUsdDaily`.
    static func usdtUsdDaily(days: Int = PortfolioHistory.maxDays) async -> [Int: Double] {
        let span = max(days, PortfolioHistory.maxDays)
        let kraken = await ownCloses(of: usdtUsdKey, pair: usdtUsdPair, span: span, usdtUsd: nil)?.closes ?? [:]
        let today = PortfolioHistory.epochDay(utcMillis: EpochTime.nowMillis)
        let window = min(span, usdtUsdFillDays)
        // Heute darf fehlen (Kerze läuft noch)
        guard (today - window + 1 ..< today).contains(where: { kraken[$0] == nil }) else { return kraken }
        let coinbase = await ownCloses(of: usdtUsdFillKey, pair: usdtUsdFillPair, span: span, usdtUsd: nil)?.closes ?? [:]
        return HistoricCloseChain.fillUsdtUsd(kraken, coinbase)
    }

    // MARK: Ausweich-Kette

    /// Nach einer Abfrage ohne Treffer so lange nicht erneut alle Börsen fragen (nur im Speicher).
    private static let chainMissMillis: Int64 = 60 * 60_000
    private static let chainParallel = 4
    /// Zwischenspeicher der Kette, zusammen hinter einer Sperre. Begrenzt (`BoundedCache`, 512 Coins wie
    /// `chainMemory`/`chainMisses` mit `MEMORY_CACHE_ENTRIES`, Android) statt unbegrenzter Wörterbücher.
    private struct ChainMemory: Sendable {
        /// Coin → (Reihe der Kette samt Weg, Abfragezeit).
        var series = BoundedCache<String, (entry: PortfolioOwnCloses, at: Int64)>()
        /// Coin → Zeit der letzten Abfrage ohne Treffer.
        var misses = BoundedCache<String, Int64>()
    }

    private static let chainMemory = OSAllocatedUnfairLock(initialState: ChainMemory())

    private static func chainCacheKey(_ coin: String) -> String { "phistc_" + coin }

    /// Anbieter einer Reihe der Kette aus ihrem Weg («Kraken|XBTUSD|NONE» → «Kraken»).
    static func chainProvider(_ route: String) -> String {
        HistoricCloseChain.provider(String(route.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""))
    }

    /// Reihen der Kette für `coins` (höchstens `chainParallel` gleichzeitig); Coins ohne Reihe fehlen.
    static func chainSeries(_ coins: [String], span: Int, usdtUsd: [Int: Double]) async -> [String: PortfolioOwnCloses] {
        var out: [String: PortfolioOwnCloses] = [:]
        // Namen der Merkliste einmal für alle Coins (nicht je Coin den ganzen Stand lesen)
        let names = coins.isEmpty ? [:] : HistoricPriceSource.watchNames(coins)
        var index = 0
        while index < coins.count {
            let batch = coins[index..<min(index + chainParallel, coins.count)]
            index += chainParallel
            await withTaskGroup(of: (String, PortfolioOwnCloses?).self) { group in
                for coin in batch {
                    group.addTask {
                        let value = await chainCloses(of: coin, span: span, usdtUsd: usdtUsd, ownName: names[coin])
                        return (coin, value)
                    }
                }
                for await (coin, entry) in group {
                    guard let entry, !entry.entry.closes.isEmpty else { continue }
                    out[coin] = entry
                }
            }
        }
        return out
    }

    /// Reihe der Kette für `coin`: Speicher, Gerät (12 h), sonst Abfrage. Neu geholt wird nur mit aktuellem Kurs als
    /// Bezug (`chainReference`) — sonst höchstens ein älterer Stand (den der Verlauf noch gegen den Kurs prüft).
    private static func chainCloses(of coin: String, span: Int, usdtUsd: [Int: Double],
                                    ownName: String?) async -> PortfolioOwnCloses? {
        let now = EpochTime.nowMillis
        let hit: (entry: PortfolioOwnCloses, at: Int64)? = chainMemory.withLock { $0.series.get(coin) }
        if let hit, hit.entry.entry.span >= span, CycleCachePolicy.isFresh(savedAt: hit.at, now: now, ttl: ttl) {
            return hit.entry
        }
        let stored = CycleCache.read(chainCacheKey(coin), as: PortfolioOwnCloses.self)
        if let stored, stored.value.entry.span >= span, CycleCachePolicy.isFresh(savedAt: stored.savedAt, now: now, ttl: ttl) {
            let entry = (entry: stored.value, at: stored.savedAt)
            chainMemory.withLock { $0.series.set(coin, entry) }
            return stored.value
        }
        let older = stored?.value ?? hit?.entry
        let missAt: Int64? = chainMemory.withLock { $0.misses.get(coin) }
        if let missAt, now - missAt >= 0, now - missAt < chainMissMillis { return older }
        guard let reference = await chainReference(coin) else { return older }
        guard let fresh = await fetchChain(coin, span: span, now: now, usdtUsd: usdtUsd, reference: reference,
                                           ownName: ownName) else {
            chainMemory.withLock { $0.misses.set(coin, now) }
            return older
        }
        chainMemory.withLock {
            $0.misses.removeValue(forKey: coin)
            $0.series.set(coin, (entry: fresh, at: now))
        }
        CycleCache.write(chainCacheKey(coin), fresh, savedAt: now)
        return fresh
    }

    /// Aktueller Kurs als Bezug: aus dem Speicher der Portfolio-Kurse, sonst der gemerkte Stand; nur geprüfte Kurse
    /// (`CutoffExport.referencePrices`). Keine eigene Kursabfrage.
    private static func chainReference(_ coin: String) async -> Double? {
        // Ohne ungeprüfte und ohne «Kurs ungeprüft» — gleiche Regel wie die Exporte, wie Android
        func layer(_ p: PortfolioPrices) -> CutoffExport.ReferenceLayer {
            CutoffExport.ReferenceLayer(prices: p.prices, unchecked: p.unchecked, unverified: p.unverified)
        }
        if let p = CutoffExport.referenceOf(coin, layers: [layer(PortfolioPriceSource.cached([coin]))]) { return p }
        let stored = await PortfolioPriceSource.stored([coin])
        return CutoffExport.referenceOf(coin, layers: [layer(stored)])
    }

    /// Quellen der Kette der Reihe nach: erste Reihe mit passendem Namen (`HistoricCloseChain.nameAllowed`) und jüngstem
    /// Schluss ±25 % am aktuellen Kurs (`HistoricCloseChain.acceptSeries`); USD-Kerzen durch USDT/USD des Tags.
    private static func fetchChain(_ coin: String, span: Int, now: Int64, usdtUsd: [Int: Double],
                                   reference: Double, ownName: String?) async -> PortfolioOwnCloses? {
        let names = TokenIdentityStore.shared
        let binanceName = names.binanceName(coin)
        for source in HistoricCloseChain.seriesChain {
            let nameOk = HistoricCloseChain.nameAllowed(base: coin, own: false, exchangeKey: source, ownName: ownName,
                                                        binanceName: binanceName,
                                                        providerName: names.pairName(exchangeKey: source, base: coin))
            guard nameOk else { continue }
            for p in HistoricCloseChain.pairs(source, base: coin) {
                let pair = HistoricPriceSource.chainCandlePair(source, base: coin, p)
                guard let entry = await fetchOwn(pair, span: span, now: now, usdtUsd: usdtUsd) else { continue }
                let latest = entry.closes.max(by: { $0.key < $1.key })?.value
                if HistoricCloseChain.acceptSeries(latest: latest, current: reference, nameOk: true) {
                    return PortfolioOwnCloses(route: pair.cacheKey, entry: entry)
                }
            }
        }
        return nil
    }
}
