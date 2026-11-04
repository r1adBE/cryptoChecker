import Foundation

// «Nur Mehrbestand, gleiche Wallet»: beobachtete Adressen zählen nur, was sie MEHR halten als die eigenen Transaktionen
// IHRER Wallet — wie `AddressSurplus.kt` (dort beschrieben).

/// EINE Regel für Adressen, die zum Portfolio zählen (`countInPortfolio`): je Wallet und Coin zählt das Guthaben nur,
/// soweit es den eigenen Bestand IN DIESER WALLET übersteigt — gezählt = max(0, Adress-Teil − eigener Bestand der Wallet)
/// (`counted`). Bestände in anderen Wallets (Börse, andere Adresse) ziehen nie etwas ab.
/// - Wallet = `AddressCostLots.walletName` (Bezeichnung, sonst gekürzte Adresse), verglichen mit `PortfolioWallets.key`.
/// - Adress-Teil der Wallet = Guthaben ihrer zählenden Adressen zusammen ohne das schon Gebuchte (Eröffnungsposten und
///   erfasste Bewegungen der Wallet bis zum Abruf: `AddressCostLots.accountedByWallet`, im Verlauf `AddressBooked.held`).
/// - Eigener Bestand der Wallet = erfasste Bewegungen dieser Wallet (`AddressCostLots.recordedMove`) ohne die, die das
///   Guthaben schon enthält (bis zum Abruf, `WalletMoves.fetched`); nie unter 0 (`outside`) — nie doppelt abgezogen. Was
///   davon das Guthaben der verrechnenden Adresse übersteigt (`WalletMoves.owner`), zieht von den übrigen Adressen der
///   Wallet ab (`ledger`; im Verlauf negative Stufen in `steps`).
/// - Mehrere zählende Adressen derselben Wallet: erst summiert, dann der eigene Bestand der Wallet EINMAL abgezogen;
///   verschiedene Wallets rechnen unabhängig.
/// Die automatische Regel «Nur anzeigen» (alle Coins innerhalb 5 %) bleibt davor. Karte, Zeilen, Detail, Alarme, Widgets
/// und Uhr (`parts` über `AddressBalance.trades`) sowie Zeiträume, Wertverlauf, Stichtag und CH-Vermögenswert (`steps`
/// über `AddressBalanceHistory.trades`) rechnen damit. Wie Android.
enum AddressSurplus {

    /// Gezählter Adress-Teil: max(0, `address` − `ledger`); ein negativer eigener Bestand zählt als 0.
    static func counted(_ address: Decimal, ledger: Decimal) -> Decimal {
        max(0, address - max(0, ledger))
    }

    /// Eigener Bestand der Wallet `net` ohne die schon im Guthaben enthaltenen Bewegungen `recorded` (Abgänge ziehen
    /// nichts ab), ≥ 0.
    static func outside(net: Decimal, recorded: Decimal) -> Decimal {
        max(0, net - max(0, recorded))
    }

    /// Erfasste Bewegungen EINER Adress-Wallet je Coin (`AddressCostLots.recordedMove`): `all` = alle eigenen
    /// Transaktionen der Wallet, `fetched` = davon die bis zum Abruf der verrechnenden Adresse `owner` (`movesOwners`,
    /// `fetched`) — sie stecken schon im Guthaben.
    struct WalletMoves: Sendable {
        let owner: WatchedAddress
        let all: [String: [AddressMove]]
        let fetched: [String: [AddressMove]]
    }

    /// Je Wallet (`PortfolioWallets.key`) einer zählenden Adresse ihre erfassten Bewegungen (`WalletMoves`).
    static func walletMoves(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [String: WalletMoves] {
        let own = real.filter { !AddressBalance.isAddressTrade($0.id) }
        guard !own.isEmpty else { return [:] }
        let owners = AddressCostLots.movesOwners(addresses)
        let coins = PortfolioLedger.coins(own)
        var out: [String: WalletMoves] = [:]
        for (a, _) in AddressCostLots.counted(addresses) {
            let wallet = AddressCostLots.walletName(a)
            let key = PortfolioWallets.key(wallet)
            guard owners[key] == a.id else { continue }
            var all: [String: [AddressMove]] = [:]
            var inBalance: [String: [AddressMove]] = [:]
            for coin in coins {
                for t in own {
                    let m = AddressCostLots.recordedMove(coin, wallet: wallet, trade: t)
                    if m == 0 { continue }
                    all[coin, default: []].append(AddressMove(time: t.time, amount: m))
                    // Bis zum Abruf wie `AddressCostLots.fetched`
                    if a.lastUpdated <= 0 || t.time <= a.lastUpdated {
                        inBalance[coin, default: []].append(AddressMove(time: t.time, amount: m))
                    }
                }
            }
            out[key] = WalletMoves(owner: a, all: all, fetched: inBalance)
        }
        return out
    }

    private static func sum(_ moves: [AddressMove]?, until: Int64 = .max) -> Decimal {
        (moves ?? []).reduce(Decimal(0)) { $1.time <= until ? $0 + $1.amount : $0 }
    }

    /// Eigener Bestand der Wallet `moves` für `coin` auf der Karte: `outside` über alle ihre Bewegungen, dazu die schon im
    /// Guthaben erwarteten Bewegungen, die das Guthaben der verrechnenden Adresse übersteigen (sie kürzte ihr Adress-Teil
    /// nur bis 0, `AddressCostLots.cover`); ohne = 0.
    static func ledger(_ moves: WalletMoves?, coin: String) -> Decimal {
        guard let moves else { return 0 }
        let recorded = sum(moves.fetched[coin])
        let observed = max(0, AddressCostLots.balances(moves.owner)[coin] ?? 0)
        return outside(net: sum(moves.all[coin]), recorded: recorded) + max(0, recorded - observed)
    }

    /// Adress-Teil je Wallet (`PortfolioWallets.key`) und Coin: Guthaben ihrer zählenden Adressen
    /// (`AddressCostLots.counted`) ohne das schon Gebuchte (`AddressCostLots.accountedByWallet`).
    static func walletParts(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [String: [String: Decimal]] {
        var out: [String: [String: Decimal]] = [:]
        for entry in AddressCostLots.counted(addresses) {
            let key = PortfolioWallets.key(AddressCostLots.walletName(entry.address))
            for (coin, v) in entry.balances { out[key, default: [:]][coin, default: 0] += v }
        }
        for (key, coins) in AddressCostLots.accountedByWallet(addresses, real: real) {
            for (coin, v) in coins { out[key, default: [:]][coin, default: 0] -= v }
        }
        return out
    }

    /// Gezählter Adress-Teil je Coin für die Karte (Reihenfolge von `AddressBalance.holdings`, nur > 0): je Wallet `counted`
    /// des Adress-Teils (`walletParts`) gegen den eigenen Bestand dieser Wallet (`ledger`), dann summiert.
    static func parts(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [(coin: String, amount: Decimal)] {
        let held = AddressBalance.holdings(addresses)
        guard !held.isEmpty else { return [] }
        let wallets = walletParts(addresses, real: real)
        let moves = walletMoves(addresses, real: real)
        return held.compactMap { item -> (coin: String, amount: Decimal)? in
            guard PortfolioDecimal.parse(item.amount) != nil else { return nil }
            var part: Decimal = 0
            for (key, coins) in wallets {
                guard let p = coins[item.coin] else { continue }
                part += counted(p, ledger: ledger(moves[key], coin: item.coin))
            }
            return part > 0 ? (coin: item.coin, amount: part) : nil
        }
    }

    /// Stufen des eigenen Bestands einer Wallet (`outside`) aus `net` und `recorded`; Zeiten auf `last` (letzte
    /// Beobachtung des Coins in dieser Wallet) begrenzt — das zuletzt geladene Guthaben steht gegen alle Buchungen der
    /// Wallet wie auf der Karte. `absorbed` = die verrechnende Adresse hat eine Reihe dieses Coins (dort gebucht, ein
    /// Übersteigen zeigt ihre negative Stufe, `steps`); sonst zählt `recorded` (> 0) ganz dazu — wie `ledger` ohne Guthaben.
    static func ledgerSteps(net: [AddressMove], recorded: [AddressMove], last: Int64,
                            absorbed: Bool = true) -> [AddressObservation] {
        let n = net.map { AddressMove(time: Swift.min($0.time, last), amount: $0.amount) }
        let r = recorded.map { AddressMove(time: Swift.min($0.time, last), amount: $0.amount) }
        return Array(Set((n + r).map(\.time))).sorted().map { t in
            let inBalance = sum(r, until: t)
            let extra = absorbed ? 0 : max(0, inBalance)
            return AddressObservation(time: t, balance: outside(net: sum(n, until: t), recorded: inBalance) + extra)
        }
    }

    /// Verlauf eines Coins in EINER Wallet: Stufen der Adress-Teile `parts` (je Adresse der Wallet, ohne Untergrenze:
    /// `AddressBooked.held` mit `floor: false`) zu jeder ihrer Zeiten — die positiven summiert, davon `counted` gegen den
    /// eigenen Bestand der Wallet `ledger` zu dieser Zeit (`ledgerSteps`) plus die negativen (Buchungen über dem Guthaben
    /// ihrer Adresse); aufsteigend.
    static func steps(_ parts: [[AddressObservation]], ledger: [AddressObservation]) -> [AddressObservation] {
        // Stabil sortiert wie Android (`sortedBy`)
        let sorted = parts.map { p in
            p.enumerated()
                .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
                .map(\.element)
        }
        return Array(Set(sorted.flatMap { $0.map(\.time) })).sorted().map { t in
            let at = sorted.map { list in list.last(where: { $0.time <= t })?.balance ?? 0 }
            let held = at.reduce(Decimal(0)) { $0 + max(0, $1) }
            let over = at.reduce(Decimal(0)) { $0 + max(0, -$1) }
            let own = AddressBalanceHistory.balanceAt(ledger, time: t) + over
            return AddressObservation(time: t, balance: counted(held, ledger: own))
        }
    }
}
