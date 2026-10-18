import Foundation

// CA: Adjusted Cost Base mit Superficial Loss — wie `TaxEngineCa.kt` (Android).

extension TaxEngines {

    /// CA: Verlust einer Zeile in der Steuerwährung (CAD) — Erlös zum Kurs des Verkaufs, Einstand je Herkunftsteil zum
    /// Kurs seines Datums (wie der Export); Rückgabe = Verlust als USDT-Betrag zum Kurs des Verkaufs, 0 = kein Verlust in
    /// CAD; nil = ein Kurs fehlt. Wie Android.
    private static func caLossAtSaleRate(_ row: PortfolioDisposal, _ fx: (Int64) -> Decimal?) -> Decimal? {
        guard let proceeds = row.proceedsUsdt, let cost = row.costUsdt,
              let rate = fx(row.sellTime), rate > 0 else { return nil }
        var costT: Decimal = 0
        if !row.costParts.isEmpty {
            for p in row.costParts {
                guard let r = fx(p.time) else { return nil }
                costT += PortfolioDecimal.multiply(p.usdt, r)
            }
        } else if cost != 0 {
            costT = PortfolioDecimal.multiply(cost, rate)
        }
        let gainT = PortfolioDecimal.multiply(proceeds, rate) - costT
        return gainT >= 0 ? 0 : PortfolioDecimal.divide(-gainT, rate)
    }

    static func caAcb(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                              _ lost: inout [PortfolioLostFeeCost],
                              _ unassigned: inout [PortfolioUnassignedFee],
                              _ fx: ((Int64) -> Decimal?)? = nil) -> [PortfolioDisposal] {
        let days: [LocalDay] = steps.map { day($0.time, zone) }
        // Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»
        var heldAfter: [Decimal] = []
        var h: Decimal = 0
        for s in steps {
            switch s.kind {
            case .acquire: h += s.qty
            case .dispose, .remove, .networkFee: h = max(0, h - s.qty)
            case .addFee: break
            }
            heldAfter.append(h)
        }
        var pool = TaxPool(zone: zone)
        var pending: [PortfolioCostPart] = []
        // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
        var replaceLeft: [Decimal] = steps.map { $0.kind == .acquire ? $0.qty : 0 }
        // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
        var pendingAt: [Int: [PortfolioCostPart]] = [:]
        var out: [PortfolioDisposal] = []
        // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
        let acquires: [Int] = steps.indices.filter { steps[$0].kind == .acquire }
        /// Erster Index in `days` mit Tag ≥ `d` (die Schritte sind zeitlich, also die Tage aufsteigend).
        func firstDayAtLeast(_ d: LocalDay) -> Int {
            var lo = 0
            var hi = days.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if days[mid] < d { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }
        /// Erste Position in `acquires` mit Index ≥ `i`.
        func firstAcquireAtLeast(_ i: Int) -> Int {
            var lo = 0
            var hi = acquires.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if acquires[mid] < i { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }

        /// Ersatzkauf (superficial loss) nur ein Erwerb im Fenster: Einzahlung ohne Kaufdatum = eigene Coins von anderswo, nie
        /// (wie UK `late`); sonst zählt das Erwerbsdatum (Einzahlung mit früherem Kaufdatum ausserhalb des Fensters: keiner).
        func replaces(_ s: TaxStep, _ from: LocalDay, _ to: LocalDay) -> Bool {
            if s.trade.type == .DEPOSIT && s.trade.acquiredTime == nil { return false }
            let a = day(s.acquired, zone)
            return !(a < from) && !(to < a)
        }

        /// Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis).
        func toAcb(_ time: Int64, _ amount: Decimal) {
            guard amount > 0 else { return }
            if pool.priced > 0 {
                pool.addCost(time, amount)
            } else {
                pending.append(PortfolioCostPart(time: time, usdt: amount))
            }
        }
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                pool.add(s.qty, cost: s.cost, time: s.acquired)
                // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                if let parts = pendingAt.removeValue(forKey: i) {
                    for p in parts { toAcb(p.time, p.usdt) }
                }
                // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                if s.cost != nil && !pending.isEmpty {
                    for p in pending { pool.addCost(p.time, p.usdt) }
                    pending = []
                }
            case .dispose:
                let taken = pool.take(s.qty)
                var list = piecesOf(taken)
                let rest = s.qty - taken.removed
                if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                let from = days[i].plusDays(-TaxTemplate.caWindowDays)
                let to = days[i].plusDays(TaxTemplate.caWindowDays)
                // Noch nicht verbrauchte Ersatz-Zugänge im Fenster und Bestand am Ende des Fensters (Binärsuche über die
                // aufsteigenden Tage: gleiche Indizes wie der frühere Durchlauf über alle Schritte)
                let lo = firstDayAtLeast(from)
                let afterTo = firstDayAtLeast(to.plusDays(1))
                let endIndex = max(i, afterTo - 1)
                var window: [Int] = []
                var k = firstAcquireAtLeast(lo)
                while k < acquires.count && acquires[k] < afterTo {
                    if replaces(steps[acquires[k]], from, to) { window.append(acquires[k]) }
                    k += 1
                }
                let bought: Decimal = window.reduce(Decimal(0)) { $0 + replaceLeft[$1] }
                let heldEnd = heldAfter[endIndex]
                let n = max(0, min(min(s.qty, bought), heldEnd))
                var denied: Decimal = 0
                var rows = emit(coin, s, list, proceeds: s.proceeds)
                for k in rows.indices {
                    // Verlust in der Steuerwährung entscheiden (CAD: gemeldet wird in CAD — ein USDT-Verlust kann in CAD
                    // ein Gewinn sein); ohne Kurse in USDT
                    // Schenkung an Ehe-/Lebenspartner: kein Verlust (ITA 73(1))
                    guard !s.spouseGift, let g = rows[k].gainUsdt else { continue }
                    let loss: Decimal = fx.flatMap { caLossAtSaleRate(rows[k], $0) } ?? max(0, -g)
                    guard loss > 0, rows[k].costUsdt != nil, s.qty > 0 else { continue }
                    let part: Decimal = n == s.qty ? loss : PortfolioDecimal.divide(loss * n, s.qty)
                    if part > 0 {
                        denied += part
                        rows[k].adjustmentUsdt = part
                    }
                }
                out += rows
                if denied > 0 && n > 0 {
                    // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                    var need = n
                    var matched: [(j: Int, q: Decimal)] = []
                    for j in window {
                        if need <= 0 { break }
                        let q = min(replaceLeft[j], need)
                        if q <= 0 { continue }
                        replaceLeft[j] -= q
                        need -= q
                        matched.append((j, q))
                    }
                    var left = denied
                    for (k, m) in matched.enumerated() {
                        let part: Decimal = k == matched.count - 1 ? left : PortfolioDecimal.divide(denied * m.q, n)
                        left -= part
                        if m.j > i {
                            pendingAt[m.j, default: []].append(PortfolioCostPart(time: s.time, usdt: part))
                        } else {
                            toAcb(s.time, part)
                        }
                    }
                }
            case .remove:
                _ = pool.take(s.qty)
            case .networkFee:
                note(&lost, coin, s, pool.dropQty(s.qty))
            case .addFee:
                noteUnassigned(&unassigned, coin, s, pool.addFee(s.time, s.cost ?? 0))
            }
        }
        return out
    }
}
