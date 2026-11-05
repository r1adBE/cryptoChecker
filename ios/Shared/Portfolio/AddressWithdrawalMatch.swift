import Foundation

// Hinweis auf eine nicht erfasste Umbuchung zu einer beobachteten Adresse — wie `AddressWithdrawalMatch.kt` (dort
// beschrieben).

/// Treffer = eigene Umbuchung (`.TRANSFER`) im Coin der Adresse aus einer ANDEREN Wallet in eine ANDERE Wallet als die
/// Adress-Wallet (`AddressCostLots.walletName`, `PortfolioWallets.key`) — die Coins stehen dann in jener Wallet und auf
/// der Adresse —, deren Menge oder Menge nach Coin-Gebühr dem Guthaben oder einem
/// beobachteten Zugang (`WatchedAddressRules.matchTargets`) auf ± 1 % entspricht — ausser eine erfasste Umbuchung bzw.
/// Einzahlung IN die Adress-Wallet entspricht derselben Menge. Nur zählende Adressen; Summen unverändert, einzige
/// Aktion «Ziel ändern» (`retarget`: Ziel der genannten Umbuchung → Adress-Wallet). Wie Android.
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
        named(matches)?.transferTo?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Die Umbuchung, die der Hinweis nennt (`targetWallet`): erster Treffer (zeitlich) mit Ziel-Namen; sonst nil.
    static func named(_ matches: [PortfolioTx]) -> PortfolioTx? {
        matches.first { !($0.transferTo?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "").isEmpty }
    }

    /// «Ziel ändern»: Umbuchung `tx` (die der Hinweis nennt) von `from` in die Adress-Wallet `to`. Wie Android.
    struct Retarget: Equatable {
        let tx: PortfolioTx
        let from: String
        let to: String
    }

    /// «Ziel ändern» an der Adresszeile von `a`: genannte Umbuchung (`named`) → Adress-Wallet; ohne Hinweis nil.
    static func retarget(_ a: WatchedAddress, txs: [PortfolioTx]) -> Retarget? {
        guard let t = named(matches(a, txs: txs)) else { return nil }
        return Retarget(tx: t, from: (t.transferTo ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                        to: AddressCostLots.walletName(a))
    }

    /// «Ziel ändern» an der Coin-Karte: genannte Umbuchung aus `matches(coin:)` → Wallet der ersten Adresse (in
    /// Listenfolge), zu der sie passt; ohne Hinweis nil. Wie Android `retargetFor`.
    static func retarget(coin: String, addresses: [WatchedAddress], txs: [PortfolioTx]) -> Retarget? {
        guard let t = named(matches(coin: coin, addresses: addresses, txs: txs)),
              let a = addresses.first(where: { x in matches(x, txs: txs).contains { $0.id == t.id } }) else { return nil }
        return Retarget(tx: t, from: (t.transferTo ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                        to: AddressCostLots.walletName(a))
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
