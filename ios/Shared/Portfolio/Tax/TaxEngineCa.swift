import Foundation

// CA: Adjusted Cost Base mit Superficial Loss — wie `TaxEngineCa.kt` (Android).

extension TaxEngines {

    /// CA: Verlust einer Zeile in der Steuerwährung (CAD) — Erlös zum Kurs des Verkaufs, Einstand je Herkunftsteil zum
    /// Kurs seines Datums (wie der Export); Rückgabe = Verlust als USDT-Betrag zum Kurs des Verkaufs, 0 = kein Verlust in
    /// CAD; nil = ein Kurs fehlt. Wie Android.
    private static func caLossAtSaleRate(_ row: PortfolioDisposal, _ fx: PortfolioPartWeight) -> Decimal? {
        guard let proceeds = row.proceedsUsdt, let cost = row.costUsdt,
              let rate = fx.of(row.sellTime), rate > 0 else { return nil }
        var costT: Decimal = 0
        if !row.costParts.isEmpty {
            // Σ Teil × Kurs seines Datums (grosse Ansichten des Pools ohne Auslesen, `weightedSum`)
            guard let sum = row.costParts.weightedSum(fx) else { return nil }
            costT = sum
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
        // Ein Gewicht für alle Zeilen (die laufende Summe je Pool wird so nur einmal gerechnet)
        let fxWeight = fx.map { PortfolioPartWeight($0) }
        let days: [LocalDay] = steps.map { day($0.time, zone) }
        let heldAfter = caHeldAfter(steps)
        var pool = TaxPool(zone: zone)
        var pending: [PortfolioCostPart] = []
        // Nicht abziehbarer Verlust aus einer Zeile mit geschätztem Wert: der ACB enthält dann einen geschätzten Teil
        var pendingEstimated = false
        var pendingAtEstimated = Set<Int>()
        // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
        var replaceLeft: [Decimal] = steps.map { $0.kind == .acquire ? $0.qty : 0 }
        // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
        var pendingAt: [Int: [PortfolioCostPart]] = [:]
        var out: [PortfolioDisposal] = []
        // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
        let acquires: [Int] = steps.indices.filter { steps[$0].kind == .acquire }
        /// Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis); `est`
        /// = aus einer Zeile mit geschätztem Wert.
        func toAcb(_ time: Int64, _ amount: Decimal, _ est: Bool) {
            guard amount > 0 else { return }
            if pool.priced > 0 {
                pool.addCost(time, amount, est: est)
            } else {
                pending.append(PortfolioCostPart(time: time, usdt: amount))
                if est { pendingEstimated = true }
            }
        }
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                pool.add(s.qty, cost: s.cost, time: s.acquired, est: s.costEstimated)
                // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                if let parts = pendingAt.removeValue(forKey: i) {
                    let est = pendingAtEstimated.remove(i) != nil
                    for p in parts { toAcb(p.time, p.usdt, est) }
                }
                // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                if s.cost != nil && !pending.isEmpty {
                    for p in pending { pool.addCost(p.time, p.usdt, est: pendingEstimated) }
                    pending = []
                    pendingEstimated = false
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
                let lo = firstAtLeast(days, from)
                let afterTo = firstAtLeast(days, to.plusDays(1))
                let endIndex = max(i, afterTo - 1)
                let window = caWindow(steps, acquires, lo, afterTo, from, to, zone)
                let bought: Decimal = window.reduce(Decimal(0)) { $0 + replaceLeft[$1] }
                let heldEnd = heldAfter[endIndex]
                let n = max(0, min(min(s.qty, bought), heldEnd))
                var denied: Decimal = 0
                var deniedEstimated = false
                var rows = emit(coin, s, list, proceeds: s.proceeds)
                for k in rows.indices {
                    guard let part = caDeniedPart(rows[k], s, n, fxWeight) else { continue }
                    denied += part
                    if rows[k].valueEstimated { deniedEstimated = true }
                    rows[k].adjustmentUsdt = part
                }
                out += rows
                if denied > 0 && n > 0 {
                    // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                    let matched = consumeReplacements(window, &replaceLeft, n)
                    var left = denied
                    for (k, m) in matched.enumerated() {
                        let part: Decimal = k == matched.count - 1 ? left : PortfolioDecimal.divide(denied, m.q, by: n)
                        left -= part
                        if m.j > i {
                            pendingAt[m.j, default: []].append(PortfolioCostPart(time: s.time, usdt: part))
                            if deniedEstimated { pendingAtEstimated.insert(m.j) }
                        } else {
                            toAcb(s.time, part, deniedEstimated)
                        }
                    }
                }
            case .remove:
                _ = pool.take(s.qty)
            case .networkFee:
                note(&lost, coin, s, pool.dropQty(s.qty))
            case .addFee:
                noteUnassigned(&unassigned, coin, s, pool.addFee(s.time, s.cost ?? 0, amount: s.trade.amountExact,
                                                                 coinFee: s.trade.coinFeeExact, est: s.costEstimated))
            }
        }
        return out
    }

    /// Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»; Teil von `caAcb`.
    private static func caHeldAfter(_ steps: [TaxStep]) -> [Decimal] {
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
        return heldAfter
    }

    /// Erste Position in den aufsteigenden `values` mit Wert ≥ `x` (binäre Suche).
    private static func firstAtLeast<T: Comparable>(_ values: [T], _ x: T) -> Int {
        var lo = 0
        var hi = values.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if values[mid] < x { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    /// Ersatzkauf (superficial loss) nur ein Erwerb im Fenster: Einzahlung ohne Kaufdatum = eigene Coins von anderswo, nie
    /// (wie UK `late`); sonst zählt das Erwerbsdatum (Einzahlung mit früherem Kaufdatum ausserhalb des Fensters: keiner).
    private static func caReplaces(_ s: TaxStep, _ from: LocalDay, _ to: LocalDay, _ zone: TimeZone) -> Bool {
        if s.trade.type == .DEPOSIT && s.trade.acquiredTime == nil { return false }
        let a = day(s.acquired, zone)
        return !(a < from) && !(to < a)
    }

    /// Ersatz-Zugänge (Indizes in `steps`) ab Schritt `lo` und vor `afterTo`, per Binärsuche in `acquires`.
    private static func caWindow(_ steps: [TaxStep], _ acquires: [Int], _ lo: Int, _ afterTo: Int,
                                 _ from: LocalDay, _ to: LocalDay, _ zone: TimeZone) -> [Int] {
        var window: [Int] = []
        var k = firstAtLeast(acquires, lo)
        while k < acquires.count && acquires[k] < afterTo {
            if caReplaces(steps[acquires[k]], from, to, zone) { window.append(acquires[k]) }
            k += 1
        }
        return window
    }

    /// Nicht abziehbarer Teil (superficial loss) des Verlusts einer Zeile des Verkaufs `s`, anteilig zur Ersatz-Menge `n`;
    /// nil = keiner. Verlust in der Steuerwährung entscheiden (CAD: gemeldet wird in CAD — ein USDT-Verlust kann in CAD ein
    /// Gewinn sein); ohne Kurse in USDT. Schenkung an Ehe-/Lebenspartner: kein Verlust (ITA 73(1)).
    private static func caDeniedPart(_ row: PortfolioDisposal, _ s: TaxStep, _ n: Decimal,
                                     _ fxWeight: PortfolioPartWeight?) -> Decimal? {
        guard !s.spouseGift, let g = row.gainUsdt else { return nil }
        let loss: Decimal = fxWeight.flatMap { caLossAtSaleRate(row, $0) } ?? max(0, -g)
        guard loss > 0, row.costUsdt != nil, s.qty > 0 else { return nil }
        let part: Decimal = n == s.qty ? loss : PortfolioDecimal.divide(loss, n, by: s.qty)
        return part > 0 ? part : nil
    }

    /// Ersatz verbrauchen (früheste zuerst) bis zur Menge `n`: (Zugang, Menge) je getroffenem Ersatz.
    private static func consumeReplacements(_ window: [Int], _ replaceLeft: inout [Decimal], _ n: Decimal) -> [(j: Int, q: Decimal)] {
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
        return matched
    }
}
