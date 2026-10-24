import Foundation

/// Löst die eingetragenen Börsen des Portfolios zu Paaren auf (`PortfolioExchangeRoute`) und legt die Wege in
/// der App Group ab (`PortfolioExchangeRouteStore`) — die Paarlisten liegen in der App (`PairCache`), Widget und
/// Hintergrund lesen nur die fertigen Wege. Fehlt die Paarliste einer Börse, wird sie höchstens alle 6 Std.
/// einmal geladen. Wie `PortfolioPriceSource.applyExchangeRoutes` (Android, dort in einem Schritt).
enum PortfolioRouteResolver {
    // nonisolated(unsafe): jeder Zugriff nur unter `lock` (NSLock)
    nonisolated(unsafe) private static var pairListAttempts: [String: Int64] = [:]
    private static let lock = NSLock()
    private static let retryMillis: Int64 = 6 * 60 * 60_000

    static func resolve(_ transactions: [PortfolioTx]) async {
        // Umbuchungen: der Bestand liegt am Ziel der letzten Umbuchung (`exchangeByLedger`)
        let (single, mixed) = PortfolioExchangeRoute.exchangeByLedger(transactions.flatMap(\.routeEntries))
        var markets: [String: String] = [:]
        for m in MarketsConfig.all { markets[m.key] = m.name }
        var routes: [String: PortfolioRoute] = [:]
        var unresolved: [String: String] = [:]
        for (coin, name) in single {
            // Binance oder freier Text: Binance, ohne Hinweis
            guard let market = PortfolioExchangeRoute.market(name, markets: markets) else { continue }
            var pairs = await PairCache.shared.pairs(for: market.key).pairs
            if PortfolioExchangeRoute.pickPair(coin, pairs: pairs) == nil && mayLoadPairs(market.key) {
                if let fresh = try? await PairCache.shared.sync(market.key) { pairs = fresh.pairs }
            }
            if let pick = PortfolioExchangeRoute.pickPair(coin, pairs: pairs) {
                routes[coin] = PortfolioRoute(marketKey: market.key, marketName: market.name,
                                              base: pick.base, quote: pick.quote, pairId: pick.pairId)
            } else {
                unresolved[coin] = market.name
            }
        }
        PortfolioExchangeRouteStore.save(routes: routes, unresolved: unresolved, mixed: mixed)
    }

    /// Coins mit einheitlich eingetragener Spot-Börse der App (nicht Binance, kein freier Text) — mit oder ohne Paar.
    /// Für den Verlauf: ohne gespeicherten Weg nie still Binance (`PortfolioHistorySource.dailyPrices`).
    static func enteredCoins(_ transactions: [PortfolioTx]) -> Set<String> {
        let single = PortfolioExchangeRoute.exchangeByLedger(transactions.flatMap(\.routeEntries)).single
        var markets: [String: String] = [:]
        for m in MarketsConfig.all { markets[m.key] = m.name }
        var out = Set<String>()
        for (coin, name) in single where PortfolioExchangeRoute.market(name, markets: markets) != nil { out.insert(coin) }
        return out
    }

    private static func mayLoadPairs(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let now = TimeUtils.nowMillis
        if let last = pairListAttempts[key], now - last >= 0, now - last < retryMillis { return false }
        pairListAttempts[key] = now
        return true
    }

    /// Namen der Spot-Börsen der App (alphabetisch) für das Feld «Börse» — wie `spotMarketNames` (Android).
    static var spotMarketNames: [String] {
        Array(Set(MarketsConfig.all.filter { PortfolioExchangeRoute.isSpot($0.key) }.map(\.name)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
}
