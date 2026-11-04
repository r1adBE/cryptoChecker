import Foundation

/// Plan der weiteren Stablecoins des Depeg-Alarms (`StableDepegCheck.Plan`): geprüfte Stablecoins aus Merkliste und
/// Portfolio (`StableDepeg.watched`) und je übriger Stablecoin ein Paar der gespeicherten Paarlisten von Kraken, Coinbase
/// und Binance (`PairCache`, es wird keine Liste geladen). Wie `StableDepegChecker.check` (Android, dort in einem Schritt).
enum StableDepegPlanner {
    static func plan() async -> StableDepegCheck.Plan {
        let (bases, holdings) = await MainActor.run { () -> ([String], [String]) in
            let data = AppData.shared
            let bases = data.watches.filter { !$0.disabled }.map(\.baseAsset)
            guard data.settings.portfolioEnabled else { return (bases, []) }
            // Coins aus Transaktionen und beobachteten Adressen (Netz-Coin und zählende Token)
            let held = data.portfolio.map(\.coin)
                + data.watchedAddresses.flatMap { [$0.chain.coin] + $0.tokenHoldings().map(\.coin) }
            return (bases, held)
        }
        let watched = StableDepeg.watched(watchBases: bases, holdings: holdings)
        let others = watched.filter { $0 != StableDepeg.usdc }
        guard !others.isEmpty else { return StableDepegCheck.Plan(watched: watched) }
        var routes: [String: [StableDepegCheck.Route]] = [:]
        for key in StableDepeg.exchanges {
            let pairs = await PairCache.shared.pairs(for: key).pairs
            for coin in others {
                guard let pair = StableDepeg.pickPair(coin, pairs: pairs) else { continue }
                routes[coin, default: []].append(StableDepegCheck.Route(exchangeKey: key, pair: pair))
            }
        }
        return StableDepegCheck.Plan(watched: watched, routes: routes)
    }
}
