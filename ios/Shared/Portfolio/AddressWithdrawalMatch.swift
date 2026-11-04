import Foundation

// Hinweis auf eine nicht erfasste Umbuchung zu einer beobachteten Adresse — wie `AddressWithdrawalMatch.kt` (dort
// beschrieben).

/// Treffer = eigene Umbuchung (`.TRANSFER`) im Coin der Adresse aus einer ANDEREN Wallet in eine ANDERE Wallet als die
/// Adress-Wallet (`AddressCostLots.walletName`, `PortfolioWallets.key`) — die Coins stehen dann in jener Wallet und auf
/// der Adresse —, deren Menge oder Menge nach Coin-Gebühr dem Guthaben oder einem
/// beobachteten Zugang (`WatchedAddressRules.matchTargets`) auf ± 1 % entspricht — ausser eine erfasste Umbuchung bzw.
/// Einzahlung IN die Adress-Wallet entspricht derselben Menge. Nur zählende Adressen; nur Hinweis. Wie Android.
enum AddressWithdrawalMatch {

    /// Treffer der Adresse `a` in den eigenen Transaktionen `txs`, zeitlich aufsteigend (stabil), je Transaktion einmal.
    static func matches(_ a: WatchedAddress, txs: [PortfolioTx]) -> [PortfolioTx] {
        guard a.countInPortfolio else { return [] }
        let targets = WatchedAddressRules.matchTargets(a)
        if targets.isEmpty { return [] }
        let own = txs.filter { !AddressBalance.isAddressTrade($0.id) }
        let wallet = AddressCostLots.walletName(a)
        let key = PortfolioWallets.key(wallet)
        func near(_ t: PortfolioTx, _ goal: Decimal) -> Bool {
            WatchedAddressRules.nearlyEqual(t.amountExact, goal) || WatchedAddressRules.nearlyEqual(t.receivedExact, goal)
        }
        let hits: [(offset: Int, element: PortfolioTx)] = own.enumerated().filter { pair -> Bool in
            let t = pair.element
            guard t.type == .TRANSFER, PortfolioWallets.key(t.transferFrom) != key,
                  PortfolioWallets.key(t.transferTo) != key else { return false }
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            let goals = (targets[coin] ?? []).filter { near(t, $0) }
            // Gleiche Menge schon als Umbuchung/Einzahlung in die Adress-Wallet erfasst: kein Hinweis
            return goals.contains { goal in
                !own.contains { r in
                    PortfolioCalculator.normalizeCoin(r.coin) == coin
                        && AddressCostLots.recordedMove(coin, wallet: wallet, trade: r) > 0 && near(r, goal)
                }
            }
        }
        return hits.sorted { l, r in
            l.element.time != r.element.time ? l.element.time < r.element.time : l.offset < r.offset
        }.map(\.element)
    }

    /// Ziel-Wallet des ersten Treffers (zeitlich) für den Hinweis «… Umbuchung nach «Ledger» …»; ohne Treffer/Namen nil.
    /// Wie Android.
    static func targetWallet(_ matches: [PortfolioTx]) -> String? {
        for t in matches {
            if let name = t.transferTo?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty { return name }
        }
        return nil
    }

    /// Coin-Karte: Treffer (`matches`) aller zählenden Adressen im Coin `coin`, je Transaktion einmal, zeitlich aufsteigend.
    static func matches(coin: String, addresses: [WatchedAddress], txs: [PortfolioTx]) -> [PortfolioTx] {
        let c = PortfolioCalculator.normalizeCoin(coin)
        var seen = Set<Int64>()
        var hits: [PortfolioTx] = []
        for a in addresses {
            for t in matches(a, txs: txs) where PortfolioCalculator.normalizeCoin(t.coin) == c {
                if seen.insert(t.id).inserted { hits.append(t) }
            }
        }
        return hits.enumerated().sorted { l, r in
            l.element.time != r.element.time ? l.element.time < r.element.time : l.offset < r.offset
        }.map(\.element)
    }
}
