import Foundation

// «Nur Mehrbestand»: beobachtete Adressen zählen nur, was sie MEHR halten als die eigenen Transaktionen — wie
// `AddressSurplus.kt` (dort beschrieben).

/// EINE Regel für Adressen, die zum Portfolio zählen (`countInPortfolio`): je Coin zählt das Guthaben nur, soweit es den
/// eigenen Bestand übersteigt — gezählt = max(0, Adress-Teil − eigener Bestand) (`counted`).
/// - Adress-Teil = Guthaben ALLER zählenden Adressen des Coins zusammen ohne das schon Gebuchte (Eröffnungsposten und
///   erfasste Bewegungen der Adress-Wallet: `AddressCostLots.accounted`, im Verlauf `AddressBooked.held`).
/// - Eigener Bestand = Bestand der eigenen Transaktionen (`PortfolioHoldingsBook`, Standardmethode) ohne die erfassten
///   Zugänge der Adress-Wallets, die das Guthaben schon enthält; nie unter 0 (`outside`).
/// - Mehrere zählende Adressen desselben Coins: erst summiert, dann der eigene Bestand EINMAL abgezogen.
/// Die automatische Regel «Nur anzeigen» (alle Coins innerhalb 5 %) bleibt davor. Karte, Zeilen, Alarme, Widgets und Uhr
/// (`parts` über `AddressBalance.trades`) sowie Zeiträume, Wertverlauf, Stichtag und CH-Vermögenswert (`steps` über
/// `AddressBalanceHistory.trades`) rechnen damit. Wie Android.
enum AddressSurplus {

    /// Gezählter Adress-Teil: max(0, `address` − `ledger`); ein negativer eigener Bestand zählt als 0.
    static func counted(_ address: Decimal, ledger: Decimal) -> Decimal {
        max(0, address - max(0, ledger))
    }

    /// Eigener Bestand `net` ohne die erfassten Zugänge der Adress-Wallets `recorded` (Abgänge ziehen nichts ab), ≥ 0.
    static func outside(net: Decimal, recorded: Decimal) -> Decimal {
        max(0, net - max(0, recorded))
    }

    /// Bestandsänderungen der eigenen Transaktionen je Coin (`PortfolioHoldingsBook`, Standardmethode), zeitlich.
    static func netMoves(_ real: [PortfolioTx]) -> [String: [AddressMove]] {
        let own = real.filter { !AddressBalance.isAddressTrade($0.id) }
        var out: [String: [AddressMove]] = [:]
        for entry in PortfolioLedger.eventsByCoin(own) {
            var book = PortfolioHoldingsBook()
            var moves: [AddressMove] = []
            for e in entry.events {
                let before = book.total
                book.apply(e)
                let delta = book.total - before
                if delta != 0 { moves.append(AddressMove(time: e.trade.time, amount: delta)) }
            }
            if !moves.isEmpty { out[entry.coin] = moves }
        }
        return out
    }

    /// Erfasste Bewegungen der Adress-Wallets je Coin (`AddressCostLots.recordedMove`): nur die verrechnende Adresse je
    /// Wallet (`movesOwners`), nur bis zum Abruf ihres Guthabens — wie `AddressBooked.histories`.
    static func recordedMoves(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [String: [AddressMove]] {
        let own = real.filter { !AddressBalance.isAddressTrade($0.id) }
        guard !own.isEmpty else { return [:] }
        let owners = AddressCostLots.movesOwners(addresses)
        let coins = PortfolioLedger.coins(own)
        var out: [String: [AddressMove]] = [:]
        for (a, _) in AddressCostLots.counted(addresses) {
            let wallet = AddressCostLots.walletName(a)
            guard owners[PortfolioWallets.key(wallet)] == a.id else { continue }
            let fetched = AddressCostLots.fetched(a, own)
            for coin in coins {
                for t in fetched {
                    let m = AddressCostLots.recordedMove(coin, wallet: wallet, trade: t)
                    if m != 0 { out[coin, default: []].append(AddressMove(time: t.time, amount: m)) }
                }
            }
        }
        return out
    }

    private static func sum(_ moves: [AddressMove]?, until: Int64 = .max) -> Decimal {
        (moves ?? []).reduce(Decimal(0)) { $1.time <= until ? $0 + $1.amount : $0 }
    }

    /// Eigener Bestand je Coin für die Karte (`outside` über alle eigenen Transaktionen).
    static func ledger(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [String: Decimal] {
        let net = netMoves(real)
        let recorded = recordedMoves(addresses, real: real)
        var out: [String: Decimal] = [:]
        for coin in Set(net.keys).union(recorded.keys) {
            out[coin] = outside(net: sum(net[coin]), recorded: sum(recorded[coin]))
        }
        return out
    }

    /// Gezählter Adress-Teil je Coin für die Karte (Reihenfolge von `AddressBalance.holdings`, nur > 0): Guthaben ohne das
    /// schon Gebuchte (`AddressCostLots.accounted`), davon `counted` gegen `ledger`.
    static func parts(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [(coin: String, amount: Decimal)] {
        let held = AddressBalance.holdings(addresses)
        guard !held.isEmpty else { return [] }
        let accounted = AddressCostLots.accounted(addresses, real: real)
        let own = ledger(addresses, real: real)
        return held.compactMap { item -> (coin: String, amount: Decimal)? in
            guard let total = PortfolioDecimal.parse(item.amount) else { return nil }
            let part = counted(total - (accounted[item.coin] ?? 0), ledger: own[item.coin] ?? 0)
            return part > 0 ? (coin: item.coin, amount: part) : nil
        }
    }

    /// Stufen des eigenen Bestands (`outside`) aus `net` und `recorded`; Zeiten auf `last` (letzte Beobachtung des Coins)
    /// begrenzt — das zuletzt geladene Guthaben steht gegen alle eigenen Transaktionen wie auf der Karte.
    static func ledgerSteps(net: [AddressMove], recorded: [AddressMove], last: Int64) -> [AddressObservation] {
        let n = net.map { AddressMove(time: Swift.min($0.time, last), amount: $0.amount) }
        let r = recorded.map { AddressMove(time: Swift.min($0.time, last), amount: $0.amount) }
        return Array(Set((n + r).map(\.time))).sorted().map { t in
            AddressObservation(time: t, balance: outside(net: sum(n, until: t), recorded: sum(r, until: t)))
        }
    }

    /// Verlauf eines Coins: Stufen der Adress-Teile `parts` (je Adresse) zu jeder ihrer Zeiten summiert, davon `counted`
    /// gegen den eigenen Bestand `ledger` zu dieser Zeit (`ledgerSteps`); aufsteigend.
    static func steps(_ parts: [[AddressObservation]], ledger: [AddressObservation]) -> [AddressObservation] {
        // Stabil sortiert wie Android (`sortedBy`)
        let clean = parts.map { p in
            p.enumerated().filter { $0.element.balance >= 0 }
                .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
                .map(\.element)
        }
        return Array(Set(clean.flatMap { $0.map(\.time) })).sorted().map { t in
            let held = clean.reduce(Decimal(0)) { s, list in s + (list.last(where: { $0.time <= t })?.balance ?? 0) }
            return AddressObservation(time: t, balance: counted(held, ledger: AddressBalanceHistory.balanceAt(ledger, time: t)))
        }
    }
}
