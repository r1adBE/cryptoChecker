import Foundation
import os

/// Löst die eingetragenen Börsen des Portfolios zu Paaren auf (`PortfolioExchangeRoute`) und legt die Wege in
/// der App Group ab (`PortfolioExchangeRouteStore`) — die Paarlisten liegen in der App (`PairCache`), Widget und
/// Hintergrund lesen nur die fertigen Wege. Fehlt die Paarliste einer Börse, wird sie höchstens alle 6 Std.
/// einmal geladen. Wie `PortfolioPriceSource.applyExchangeRoutes` (Android, dort in einem Schritt).
enum PortfolioRouteResolver {
    /// Letzter Ladeversuch der Paarliste je Börse (Millisekunden), hinter einer Sperre mit Zustand.
    private static let pairListAttempts = OSAllocatedUnfairLock(initialState: [String: Int64]())
    private static let retryMillis: Int64 = 6 * 60 * 60_000

    static func resolve(_ transactions: [PortfolioTx]) async {
        // Umbuchungen: der Bestand liegt am Ziel der letzten Umbuchung (`exchangeByLedger`)
        let (single, mixed) = PortfolioExchangeRoute.exchangeByLedger(transactions.flatMap(\.routeEntries))
        var exchanges: [String: String] = [:]
        for m in ExchangesConfig.all { exchanges[m.key] = m.name }
        var routes: [String: PortfolioRoute] = [:]
        var unresolved: [String: String] = [:]
        for (coin, name) in single {
            // Binance oder freier Text: Binance, ohne Hinweis
            guard let exchange = PortfolioExchangeRoute.exchange(name, exchanges: exchanges) else { continue }
            var pairs = await PairCache.shared.pairs(for: exchange.key).pairs
            if PortfolioExchangeRoute.pickPair(coin, pairs: pairs) == nil && mayLoadPairs(exchange.key) {
                if let fresh = try? await PairCache.shared.sync(exchange.key) { pairs = fresh.pairs }
            }
            if let pick = PortfolioExchangeRoute.pickPair(coin, pairs: pairs) {
                routes[coin] = PortfolioRoute(exchangeKey: exchange.key, exchangeName: exchange.name,
                                              base: pick.base, quote: pick.quote, pairId: pick.pairId)
            } else {
                unresolved[coin] = exchange.name
            }
        }
        PortfolioExchangeRouteStore.save(routes: routes, unresolved: unresolved, mixed: mixed)
    }

    /// Coins mit einheitlich eingetragener Spot-Börse der App (nicht Binance, kein freier Text) — mit oder ohne Paar.
    /// Für den Verlauf: ohne gespeicherten Weg nie still Binance (`PortfolioHistorySource.dailyPrices`).
    static func enteredCoins(_ transactions: [PortfolioTx]) -> Set<String> {
        let single = PortfolioExchangeRoute.exchangeByLedger(transactions.flatMap(\.routeEntries)).single
        var exchanges: [String: String] = [:]
        for m in ExchangesConfig.all { exchanges[m.key] = m.name }
        var out = Set<String>()
        for (coin, name) in single where PortfolioExchangeRoute.exchange(name, exchanges: exchanges) != nil { out.insert(coin) }
        return out
    }

    private static func mayLoadPairs(_ key: String) -> Bool {
        let now = EpochTime.nowMillis
        let retry = retryMillis
        return pairListAttempts.withLock { attempts in
            if let last = attempts[key], now - last >= 0, now - last < retry { return false }
            attempts[key] = now
            return true
        }
    }

    /// Namen der Spot-Börsen der App (alphabetisch) für das Feld «Börse» — wie `spotExchangeNames` (Android).
    static var spotExchangeNames: [String] {
        Array(Set(ExchangesConfig.all.filter { PortfolioExchangeRoute.isSpot($0.key) }.map(\.name)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
}
