import Foundation

// Beobachtete Adressen am Stichtag in der Sicht der Portfolio-Karte (Stichtag-CSV, CH-Vermögenswert) — wie
// `AddressCutoff.kt` (Android).

/// Adressen am Stichtag (`AddressCutoff.at`), gerechnet wie Karte und Wertverlauf (`AddressBooked`): `lots` = wirksame
/// Eröffnungsposten der Karte (`AddressCostLots.trades`) mit Zeit ≤ Stichtag als Käufe (Wallet = Adresse, negative Id) —
/// ab ihrem Kaufdatum, auch vor der ersten Beobachtung; `part` = Adress-Teil ohne Einstand je Coin = Stufe der Reihe ohne
/// das schon Gebuchte (`AddressBooked.held`) zur letzten Beobachtung ≤ Stichtag (> 0) samt nicht beobachteter Coins;
/// `lotsAfterObservation` = ein Posten liegt zwischen der benutzten Beobachtung und dem Stichtag (das Guthaben jener
/// Beobachtung kennt ihn noch nicht; ist er wegen eines tieferen heutigen Guthabens gekürzt, kann er fehlen — Hinweiszeile,
/// Vermerk an der Summe). Wie Android.
struct AddressCutoffView: Equatable, Sendable {
    var lots: [PortfolioTx] = []
    var part = AddressBalancesAt(balances: [:], unobserved: [])
    var lotsAfterObservation = false
}

/// Stichtag-CSV und CH-Vermögenswert rechnen je Coin mit DERSELBEN Regel wie Karte und Wertverlauf (`AddressBooked`):
/// eigene Buchungen bis zum Stichtag + Posten ab ihrem Kaufdatum (Abdeckung gegen das heutige Guthaben wie auf der Karte,
/// `AddressCostLots.coverage`) + Adress-Teil ohne Einstand = Reihe ohne das schon Gebuchte am Stichtag. Vor der ersten
/// Beobachtung zählt die Adresse nicht (Coin «nicht beobachtet»), ihre Posten schon. So zeigen Karte, Verlauf, Stichtag
/// und CH-Vermögen für denselben Tag dieselbe Menge. Wie Android.
enum AddressCutoff {

    /// Adressen `addresses` am Stichtag `cutoffMillis` mit den echten Transaktionen `real` (Adress-Einträge fallen weg).
    static func at(_ addresses: [WatchedAddress], real: [PortfolioTx], cutoffMillis: Int64) -> AddressCutoffView {
        let own = real.filter { !AddressBalance.isAddressTrade($0.id) }
        let lots = AddressCostLots.trades(addresses, real: own).filter { $0.time <= cutoffMillis }
        let histories = AddressBooked.histories(addresses, real: own)
        var part: [String: Decimal] = [:]
        for (coin, steps) in AddressBalanceHistory.stepsByCoin(histories) {
            let key = PortfolioCalculator.normalizeCoin(coin)
            part[key] = (part[key] ?? 0) + AddressBalanceHistory.balanceAt(steps, time: cutoffMillis)
        }
        let unobserved = AddressBalanceHistory.balancesAt(histories, time: cutoffMillis).unobserved
        return AddressCutoffView(lots: lots, part: AddressBalancesAt(balances: part.filter { $0.value > 0 }, unobserved: unobserved),
                                 lotsAfterObservation: lateLots(addresses, cutoffMillis: cutoffMillis))
    }

    /// Ein Posten mit Zeit ≤ Stichtag nach der letzten Beobachtung seines Coins ≤ Stichtag (siehe `AddressCutoffView`).
    private static func lateLots(_ addresses: [WatchedAddress], cutoffMillis: Int64) -> Bool {
        for (a, _) in AddressCostLots.counted(addresses) {
            var seen: [String: Int64] = [:]
            for h in AddressBalanceHistory.of([a]) {
                guard let at = AddressBalanceHistory.clean(h.observations).last(where: { $0.time <= cutoffMillis }) else { continue }
                let coin = PortfolioCalculator.normalizeCoin(h.coin)
                seen[coin] = max(seen[coin] ?? .min, at.time)
            }
            if AddressCostLots.clean(a.costLots).contains(where: { l in
                l.time <= cutoffMillis && seen[l.coin].map { l.time > $0 } == true
            }) { return true }
        }
        return false
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
