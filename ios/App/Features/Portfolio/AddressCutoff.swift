import Foundation

// Beobachtete Adressen am Stichtag in der Sicht der Portfolio-Karte (Stichtag-CSV, CH-Vermögenswert) — wie
// `AddressCutoff.kt` (Android).

/// Adressen am Stichtag (`AddressCutoff.at`), gerechnet wie die Karte (`AddressBalance.trades`): `lots` = wirksame
/// Eröffnungsposten als Käufe (Wallet = Adresse, negative Id), `part` = Adress-Teil ohne Einstand je Coin (> 0) samt
/// nicht beobachteter Coins, `lotsAfterObservation` = ein Posten liegt zwischen der benutzten Beobachtung und dem Stichtag
/// (das Guthaben jener Beobachtung kennt ihn noch nicht — Hinweiszeile, Vermerk an der Summe). Wie Android.
struct AddressCutoffView: Equatable, Sendable {
    var lots: [PortfolioTx] = []
    var part = AddressBalancesAt(balances: [:], unobserved: [])
    var lotsAfterObservation = false
}

/// Stichtag-CSV und CH-Vermögenswert rechnen je Coin wie die Karte: eigene Buchungen bis zum Stichtag + Posten +
/// Adress-Teil ohne Einstand — statt Buchungen + volles Adress-Guthaben. Je zählender Adresse und Coin: Guthaben = letzte
/// Beobachtung ≤ Stichtag (auch 0; vorher nicht beobachtet: Coin «nicht beobachtet», seine Posten warten); erfasste
/// Bewegungen der Adress-Wallet (nur die verrechnende Adresse je Wallet) bis zur Zeit DIESER Beobachtung — nur sie stecken
/// schon in ihrem Guthaben; Posten mit Zeit ≤ Stichtag gegen den Bestand vor diesen Bewegungen (`AddressCostLots.cover`),
/// Rest ohne Einstand. Wie Android.
enum AddressCutoff {

    /// Guthaben einer Adresse am Stichtag je Coin (auch 0), Zeit der benutzten Beobachtung, nicht beobachtete Coins.
    private struct Observed {
        var balances: [(coin: String, balance: Decimal)] = []
        var times: [String: Int64] = [:]
        var unobserved: Set<String> = []
    }

    /// Wie `AddressBalanceHistory.balancesAt` für EINE Adresse, aber mit Guthaben 0 und der Zeit der Beobachtung.
    private static func observedAt(_ a: WatchedAddress, cutoffMillis: Int64) -> Observed {
        var out = Observed()
        for h in AddressBalanceHistory.of([a]) {
            let coin = PortfolioCalculator.normalizeCoin(h.coin)
            let obs = AddressBalanceHistory.clean(h.observations)
            guard !obs.isEmpty else { continue }
            guard let at = obs.last(where: { $0.time <= cutoffMillis }) else {
                out.unobserved.insert(coin)
                continue
            }
            if let i = out.balances.firstIndex(where: { $0.coin == coin }) {
                out.balances[i].balance += at.balance
            } else {
                out.balances.append((coin, at.balance))
            }
            // Zwei Reihen desselben Coins: die jüngere Beobachtung bestimmt, welche Bewegungen schon enthalten sind
            out.times[coin] = max(out.times[coin] ?? .min, at.time)
        }
        return out
    }

    /// Adressen `addresses` am Stichtag `cutoffMillis` mit den echten Transaktionen `real` (Adress-Einträge fallen weg).
    static func at(_ addresses: [WatchedAddress], real: [PortfolioTx], cutoffMillis: Int64) -> AddressCutoffView {
        let own = real.filter { !AddressBalance.isAddressTrade($0.id) && $0.time <= cutoffMillis }
        let owners = AddressCostLots.movesOwners(addresses)
        var lots: [PortfolioTx] = []
        var part: [String: Decimal] = [:]
        var unobserved: Set<String> = []
        var late = false
        for (a, _) in AddressCostLots.counted(addresses) {
            let seen = observedAt(a, cutoffMillis: cutoffMillis)
            unobserved.formUnion(seen.unobserved)
            let all = AddressCostLots.clean(a.costLots)
            let due = all.filter { $0.time <= cutoffMillis }
            let wallet = AddressCostLots.walletName(a)
            let moves = owners[PortfolioWallets.key(wallet)] == a.id
            for (coin, balance) in seen.balances {
                let time = seen.times[coin] ?? cutoffMillis
                let coinLots = due.filter { $0.coin == coin }
                let recorded: Decimal = moves
                    ? AddressCostLots.recorded(coin, wallet: wallet, trades: own.filter { $0.time <= time }) : 0
                let c = AddressCostLots.cover(coin, coinLots, balance: balance, recorded: recorded)
                for l in c.lots {
                    let index = all.firstIndex(where: { $0.id == l.id }) ?? 0
                    lots.append(PortfolioTx(id: AddressCostLots.tradeId(addressId: a.id, index: index), coin: coin,
                                            type: .BUY, amountExact: l.amount, priceExact: l.price, time: l.time,
                                            exchange: wallet))
                }
                // Rest ohne Einstand = Guthaben − abgebildeter Teil (`Coverage.accounted`)
                part[coin] = (part[coin] ?? 0) + c.unlotted
                if coinLots.contains(where: { $0.time > time }) { late = true }
            }
        }
        return AddressCutoffView(lots: lots, part: AddressBalancesAt(balances: part.filter { $0.value > 0 }, unobserved: unobserved),
                                 lotsAfterObservation: late)
    }

    /// Bestand je Coin am Stichtag wie die Karte: echte Transaktionen ≤ `cutoffMillis` und die Posten von `view` nach
    /// `method` (`PortfolioHoldingsBook`; Posten in ihrer Wallet = der Adresse wie in `PortfolioFifo` auf der Karte, damit
    /// eine Umbuchung weg oder ein Verkauf dort sie verbraucht). Ohne Adress-Teil (`CutoffExport.addressRows`); Staub = 0;
    /// alphabetisch. Wie Android.
    static func holdingsAt(_ real: [PortfolioTx], view: AddressCutoffView?, cutoffMillis: Int64,
                           method: CostMethod = .defaultMethod) -> [CutoffHolding] {
        let trades = real.filter { !AddressBalance.isAddressTrade($0.id) && $0.time <= cutoffMillis }
            + (view?.lots ?? []).filter { $0.time <= cutoffMillis }
        var out: [CutoffHolding] = []
        for entry in PortfolioLedger.eventsByCoin(trades) {
            var book = PortfolioHoldingsBook(method: method)
            for e in entry.events { book.apply(e) }
            if book.total > 0 && !PortfolioDecimal.isDust(book.total) { out.append(CutoffHolding(coin: entry.coin, amount: book.total)) }
        }
        return out.sorted { $0.coin < $1.coin }
    }
}
