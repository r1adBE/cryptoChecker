import Foundation

// UK: Share Pooling (gleicher Tag, 30 Tage, Section-104-Pool) — wie `TaxEngineUk.kt` (Android).

extension TaxEngines {

    static func ukPool(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                               _ lost: inout [PortfolioLostFeeCost],
                               _ unassigned: inout [PortfolioUnassignedFee]) -> [PortfolioDisposal] {
        // Netzwerkgebühren sind hier schon eigene Verkäufe (`applyRules`, HMRC CRYPTO22280) — sie zählen so in der
        // Same-Day-Gruppe ihres Tags und in der 30-Tage-Regel wie jeder andere Abgang
        // Einzahlung ohne Kaufdatum: eigene Coins von anderswo sind kein Erwerb an diesem Tag (CRYPTO22200 ordnet nur
        // Erwerbe zu) — direkt in den Pool, nicht «gleicher Tag»/«30 Tage»; sie dürfen keinen früheren Verkauf umleiten
        func late(_ s: TaxStep) -> Bool { s.trade.type == .DEPOSIT && s.trade.acquiredTime == nil }
        // Einzahlung mit früherem ursprünglichem Kaufdatum: Umbuchung zwischen eigenen Wallets ist kein Erwerb — erworben
        // wurde am Kaufdatum, also «gleicher Tag»/«30 Tage» nach dem Kaufdatum (Teil des Tages-Zugangs dieses Tags); den
        // Rest übernimmt der Pool erst am BUCHUNGSTAG (die App kennt die Coins erst ab da, siehe Schritt 3)
        func moved(_ s: TaxStep) -> Bool { day(s.acquired, zone) != day(s.time, zone) }
        // Zugänge je Tag (Erwerbsdatum) als ein Zugang zum Durchschnitt
        var dayPools: [LocalDay: TaxPool] = [:]
        var dayFirst: [LocalDay: Int64] = [:]
        // Menge je Tag noch nicht im Pool (alle Zugänge) bzw. am Tag selbst gebucht (ohne nachgebuchte Einzahlungen)
        var dayLeft: [LocalDay: Decimal] = [:]
        var dayOwn: [LocalDay: Decimal] = [:]
        for s in steps where s.kind == .acquire && !late(s) {
            let d = day(s.acquired, zone)
            var pool = dayPools[d] ?? TaxPool()
            pool.add(s.qty, cost: s.cost, time: s.acquired)
            dayPools[d] = pool
            dayFirst[d] = min(dayFirst[d] ?? Int64.max, s.acquired)
            dayLeft[d] = (dayLeft[d] ?? 0) + s.qty
            if !moved(s) { dayOwn[d] = (dayOwn[d] ?? 0) + s.qty }
        }
        let poolDays = dayPools.keys.sorted()
        let disposeIdx: [Int] = steps.indices.filter { steps[$0].kind == .dispose }
        var remaining: [Int: Decimal] = [:]
        var pieces: [Int: [Piece]] = [:]
        for i in disposeIdx {
            remaining[i] = steps[i].qty
            pieces[i] = []
        }
        // 1. Gleicher Tag: alle Abgänge des Tags gemeinsam gegen die Zugänge des Tags, anteilig nach Menge
        var groups: [(day: LocalDay, idx: [Int])] = []
        for i in disposeIdx {
            let d = day(steps[i].time, zone)
            if let last = groups.indices.last, groups[last].day == d {
                groups[last].idx.append(i)
            } else if let k = groups.firstIndex(where: { $0.day == d }) {
                groups[k].idx.append(i)
            } else {
                groups.append((d, [i]))
            }
        }
        for g in groups {
            guard var pool = dayPools[g.day] else { continue }
            let total: Decimal = g.idx.reduce(Decimal(0)) { $0 + steps[$1].qty }
            let matched = min(total, pool.held)
            guard matched > 0 else { continue }
            var left = matched
            for (n, i) in g.idx.enumerated() {
                let want: Decimal = n == g.idx.count - 1 ? left : PortfolioDecimal.divide(matched * steps[i].qty, total)
                left -= want
                let taken = pool.take(want)
                remaining[i] = (remaining[i] ?? 0) - taken.removed
                pieces[i, default: []] += piecesOf(taken, match: .SAME_DAY, matchedTime: dayFirst[g.day])
            }
            dayPools[g.day] = pool
        }
        // 2. 30 Tage: früheste Veräusserung zuerst, Zugänge der 30 Tage danach früheste zuerst
        for i in disposeIdx {
            let d = day(steps[i].time, zone)
            let to = d.plusDays(TaxTemplate.ukBbDays)
            for ad in poolDays where d < ad && !(to < ad) {
                let r = remaining[i] ?? 0
                if r <= 0 { break }
                guard var pool = dayPools[ad], pool.held > 0 else { continue }
                let taken = pool.take(r)
                dayPools[ad] = pool
                remaining[i] = r - taken.removed
                pieces[i, default: []] += piecesOf(taken, match: .THIRTY_DAY, matchedTime: dayFirst[ad])
            }
        }
        // 3. Section-104-Pool: Rest der Tages-Zugänge am Tag des Zugangs (Anteil nachgebuchter Einzahlungen erst an ihrem
        // Buchungstag), Rest der Abgänge aus dem Pool
        var pool = TaxPool(zone: zone)
        var added = Set<LocalDay>()
        var out: [PortfolioDisposal] = []
        /// Anteil `q` der noch nicht übernommenen Menge des Tages `d` vom Rest seines Tages-Zugangs in den Pool (zuletzt: alles)
        func enter(_ d: LocalDay, _ q: Decimal) {
            guard var dp = dayPools[d] else { return }
            let left = dayLeft[d] ?? 0
            dayLeft[d] = left - q
            if q >= left {
                pool.absorb(&dp)
                dayPools[d] = dp
                return
            }
            let taken = dp.take(PortfolioDecimal.divide(dp.held * q, left))
            dayPools[d] = dp
            pool.addCarried(taken.removedPriced, from: taken.parts)
            pool.add(taken.removedUnpriced, cost: nil, time: dayFirst[d] ?? 0)
        }
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                let d = day(s.acquired, zone)
                if late(s) {
                    pool.add(s.qty, cost: s.cost, time: s.acquired)
                } else if moved(s) {
                    enter(d, s.qty)
                } else if added.insert(d).inserted {
                    enter(d, dayOwn[d] ?? 0)
                }
            case .dispose:
                var list = pieces[i] ?? []
                let r = remaining[i] ?? 0
                if r > 0 {
                    let taken = pool.take(r)
                    list += piecesOf(taken, match: .POOL)
                    let rest = r - taken.removed
                    if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                }
                // Erlös vor Gebühren mitführen: die Meldegrenze an HMRC prüft die Erlöse vor Abzug der Kosten
                out += emit(coin, s, list, proceeds: s.proceeds, gross: s.gross)
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
