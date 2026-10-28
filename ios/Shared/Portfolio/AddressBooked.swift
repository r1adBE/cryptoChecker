import Foundation

// Beobachtete Adress-Reihen in der Sicht der Karte: ohne den Teil, den die App schon bucht — wie `AddressBooked.kt`.

/// Bewegung, die ein beobachtetes Guthaben schon enthält und die App selbst bucht: Zeit (ms, höchstens die letzte
/// Beobachtung der Reihe) und Menge (+ Zugang, − Abgang).
struct AddressMove: Equatable, Sendable {
    let time: Int64
    let amount: Decimal
}

/// EINE Bestandsregel für beobachtete Adressen in Karte, «Heute», 7 T / 1 J, Wertverlauf, Widget und Stichtag: eigene
/// Buchungen + Eröffnungsposten (Käufe ab ihrer Zeit) + Adress-Teil ohne Einstand = beobachtetes Guthaben ohne den Teil,
/// den Posten und erfasste Bewegungen der Adress-Wallet schon abbilden (`AddressCostLots.Coverage.accounted`).
/// Für Zeiträume und Verlauf je Adresse und Coin zu jeder Beobachtung (Zeit t): max(Guthaben(t) − gebuchte Bewegungen bis
/// t, 0) — `held`. Gebuchte Bewegungen = wirksame Posten (`AddressCostLots.coverage`) und erfasste Bewegungen der Wallet
/// (`AddressCostLots.recordedMove`, nur die verrechnende Adresse, nur bis zum Abruf des Guthabens); ihre Zeit wird auf die
/// letzte Beobachtung begrenzt (das zuletzt geladene Guthaben enthält sie wie auf der Karte). Vor der ersten Beobachtung
/// zählt die Adresse nicht; die letzte Stufe ist der Adress-Teil der Karte. Regeln wie Android (dort beschrieben).
enum AddressBooked {

    /// Reihen wie `AddressBalanceHistory.of`, an der ersten Reihe je Adresse und Coin die gebuchten Bewegungen (`moves`);
    /// `real` = echte Transaktionen (Adress-Einträge zählen nicht).
    static func histories(_ addresses: [WatchedAddress], real: [PortfolioTx]) -> [AddressBalanceHistory] {
        var out = AddressBalanceHistory.of(addresses)
        let owners = real.isEmpty ? [:] : AddressCostLots.movesOwners(addresses)
        for (a, _) in AddressCostLots.counted(addresses) {
            let wallet = AddressCostLots.walletName(a)
            let lots = AddressCostLots.coverage(a, trades: real, all: addresses)
            // Nur bis zum Abruf des Guthabens: spätere Buchungen stecken noch nicht darin
            let recorded: [PortfolioTx] = owners[PortfolioWallets.key(wallet)] != a.id ? []
                : real.filter { a.lastUpdated <= 0 || $0.time <= a.lastUpdated }
            let mine = out.indices.filter {
                out[$0].chain == a.chain && WatchedAddressRules.sameAddress(a.chain, out[$0].address, a.address)
            }
            var coins: [String] = []
            for i in mine where !coins.contains(PortfolioCalculator.normalizeCoin(out[i].coin)) {
                coins.append(PortfolioCalculator.normalizeCoin(out[i].coin))
            }
            for coin in coins {
                let series = mine.filter { PortfolioCalculator.normalizeCoin(out[$0].coin) == coin }
                guard let first = series.first else { continue }
                let last = series.compactMap { i in out[i].observations.map(\.time).max() }.max() ?? 0
                var moves = lots.filter { $0.coin == coin }.flatMap(\.lots)
                    .map { AddressMove(time: Swift.min($0.time, last), amount: $0.amount) }
                for t in recorded {
                    let amount = AddressCostLots.recordedMove(coin, wallet: wallet, trade: t)
                    if amount != 0 { moves.append(AddressMove(time: Swift.min(t.time, last), amount: amount)) }
                }
                if !moves.isEmpty { out[first].moves = moves }
            }
        }
        return out
    }

    /// Gleiche Adresse und gleicher Coin (Reihen, die `held` zusammen rechnet).
    static func sameGroup(_ x: AddressBalanceHistory, _ y: AddressBalanceHistory) -> Bool {
        x.chain == y.chain && WatchedAddressRules.sameAddress(x.chain, x.address, y.address)
            && PortfolioCalculator.normalizeCoin(x.coin) == PortfolioCalculator.normalizeCoin(y.coin)
    }

    /// Stufen der Reihen `group` (eine Adresse, ein Coin) ohne gebuchte Bewegungen: zu jeder Beobachtungszeit Summe der
    /// zuletzt beobachteten Guthaben minus Bewegungen bis dahin, nie unter 0; aufsteigend.
    static func held(_ group: [AddressBalanceHistory]) -> [AddressObservation] {
        let series = group.map { h in
            h.observations.enumerated().filter { $0.element.balance >= 0 }
                .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
                .map(\.element)
        }
        let moves = group.flatMap(\.moves)
        let times = Array(Set(series.flatMap { $0.map(\.time) })).sorted()
        return times.map { t in
            let observed = series.reduce(Decimal(0)) { sum, list in sum + (list.last(where: { $0.time <= t })?.balance ?? 0) }
            let booked = moves.filter { $0.time <= t }.reduce(Decimal(0)) { $0 + $1.amount }
            return AddressObservation(time: t, balance: Swift.max(observed - booked, 0))
        }
    }
}
