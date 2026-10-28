import Foundation

// Gewinne-Export und Jahresübersicht aus möglichst wenigen Rechnungen — dieselben Ergebnisse wie `fxSpan`, `gainRows`,
// `lostFeeCosts` und `unassignedFees` einzeln, nur ohne die Veräusserungen mehrmals zu rechnen.

extension TaxExport {

    /// Veräusserungen einer Vorlage, EINMAL je Export gerechnet (`TaxEngines.disposals` ohne Devisenkurs, alle Jahre):
    /// daraus die Devisenspanne (`span`, wie `fxSpan`) und die Zeilen (`gainRows`, wie `TaxExport.gainRows`). Der
    /// Devisenkurs `fx` wirkt nur in der CA-Rechnung (Superficial Loss in CAD); dort hängen die Kurse von der Spanne ab,
    /// darum rechnet `gainRows` für `.CA_ACB` ein zweites Mal mit den Kursen. Alle anderen Vorlagen nutzen
    /// die eine Rechnung für beides.
    struct GainsRun {
        let trades: [PortfolioTx]
        let method: TaxMethod
        let period: TaxPeriod
        let timeZone: TimeZone
        let priceAt: ((String, Int64) -> Decimal?)?
        let rules: TaxEventRules?
        let walletFrom: LocalDay?
        /// Alle Veräusserungen ohne Devisenkurs, nicht nach Jahr gefiltert (sortiert wie `TaxEngines.disposals`).
        let all: [PortfolioDisposal]

        init(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
             priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil, walletFrom: LocalDay? = nil) {
            self.trades = trades
            self.method = method
            self.period = period
            self.timeZone = timeZone
            self.priceAt = priceAt
            self.rules = rules
            self.walletFrom = walletFrom
            all = GainsRun.disposals(trades, method: method, timeZone: timeZone, priceAt: priceAt, fx: nil,
                                     rules: rules, walletFrom: walletFrom)
        }

        /// Mit diesen Eingaben gerechnet? Prüft Methode, Steuerjahr, Zeitzone, Regeln und Wallet-Beginn; `trades` und
        /// `priceAt` (Closure) lassen sich nicht vergleichen — dafür bürgt der Aufrufer (`yearSummary(run:)`).
        func matches(method: TaxMethod, period: TaxPeriod, timeZone: TimeZone, rules: TaxEventRules?,
                     walletFrom: LocalDay?) -> Bool {
            guard self.method == method, self.period == period, self.timeZone == timeZone else { return false }
            return self.rules == rules && self.walletFrom == walletFrom
        }

        /// Wie `TaxExport.disposals(in:)` ohne den Jahresfilter.
        private static func disposals(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                                      priceAt: ((String, Int64) -> Decimal?)?, fx: ((Int64) -> Decimal?)?,
                                      rules: TaxEventRules?, walletFrom: LocalDay?) -> [PortfolioDisposal] {
            TaxEngines.disposals(trades.filter { $0.amountExact > 0 }, method: method, timeZone: timeZone,
                                 priceAt: priceAt, fx: fx, rules: rules, walletFrom: walletFrom)
        }

        /// Veräusserungen mit Verkaufsdatum in `range`.
        private func sold(in range: TaxPeriod, from list: [PortfolioDisposal]) -> [PortfolioDisposal] {
            let zone = timeZone
            return list.filter { range.contains(TaxExport.localDay($0.sellTime, timeZone: zone)) }
        }

        /// Gleich wie `TaxExport.fxSpan` mit denselben Eingaben.
        func span(until: LocalDay? = nil, today: LocalDay? = nil) -> (from: LocalDay, until: LocalDay) {
            let used = sold(in: TaxExport.spanPeriod(method, period: period), from: all)
            return TaxExport.span(of: used, period: period, timeZone: timeZone, until: until, today: today)
        }

        /// Gleich wie `TaxExport.gainRows` mit denselben Eingaben.
        func gainRows(currency: String, rates: [Int: Decimal]?, usdtUsd: [Int: Decimal]? = nil) -> [TaxGainRow] {
            let fx = TaxExport.fxAt(rates, timeZone: timeZone, currency: currency, usdtUsd: usdtUsd)
            let source = method == .CA_ACB
                ? GainsRun.disposals(trades, method: method, timeZone: timeZone, priceAt: priceAt, fx: fx, rules: rules,
                                     walletFrom: walletFrom)
                : all
            return TaxExport.gainRows(of: sold(in: period, from: source), trades: trades, method: method,
                                      timeZone: timeZone, currency: currency, rates: rates, rules: rules,
                                      usdtUsd: usdtUsd)
        }
    }

    /// `lostFeeCosts(in:)` und `unassignedFees(in:)` aus EINER Rechnung (ein Buch je Coin bzw. ein Lauf der Vorlage) —
    /// gleiche Listen wie beide einzeln.
    static func feeNotes(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                         rules: TaxEventRules? = nil)
        -> (lostFeeCosts: [PortfolioLostFeeCost], unassignedFees: [PortfolioUnassignedFee]) {
        let r = trades.filter { !AddressBalance.isAddressPart($0.id) && $0.amountExact > 0 }
        let lost: [PortfolioLostFeeCost]
        let unassigned: [PortfolioUnassignedFee]
        if let cm = method.costMethod {
            let applied = TaxEngines.applyRules(r, rules: rules ?? .of(method), priceAt: nil)
            let ledgers = PortfolioCalculator.ledgers(applied, method: cm, withParts: false)
            lost = ledgers.flatMap(\.lostFeeCosts)
            unassigned = ledgers.flatMap(\.unassignedFees)
        } else {
            let notes = TaxEngines.feeNotes(r, method: method, timeZone: timeZone, rules: rules)
            lost = notes.lostFeeCosts
            unassigned = notes.unassignedFees
        }
        let inPeriod: (Int64) -> Bool = { period.contains(localDay($0, timeZone: timeZone)) }
        return (lost.filter { inPeriod($0.time) }.sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) },
                unassigned.filter { inPeriod($0.time) }
                    .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) })
    }
}
