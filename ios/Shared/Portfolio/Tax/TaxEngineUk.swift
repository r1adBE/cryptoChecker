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
            pool.add(s.qty, cost: s.cost, time: s.acquired, est: s.costEstimated)
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
        // Alle Abgänge eines Tags sind EINE Veräusserung (CRYPTO22200): jede Zuordnung (gleicher Tag, 30 Tage, Pool) gilt
        // der Gruppe und wird anteilig nach dem offenen Rest ihrer Abgänge verteilt — Menge `q`, letzter Abgang mit Rest
        // bekommt den Rest (Summe exakt `q`); `take` nimmt je Abgang seinen Anteil. Wie Android `split`.
        func split(_ idx: [Int], _ q: Decimal, _ take: (Int, Decimal) -> Void) {
            let total: Decimal = idx.reduce(Decimal(0)) { $0 + (remaining[$1] ?? 0) }
            guard q > 0, total > 0 else { return }
            let last = idx.lastIndex { (remaining[$0] ?? 0) > 0 }
            var left = q
            for (n, i) in idx.enumerated() {
                let r = remaining[i] ?? 0
                guard r > 0 else { continue }
                let want: Decimal = n == last ? left : PortfolioDecimal.divide(q, r, by: total)
                left -= want
                take(i, want)
            }
        }
        func openOf(_ idx: [Int]) -> Decimal { idx.reduce(Decimal(0)) { $0 + (remaining[$1] ?? 0) } }
        // Gruppen je Tag in der Reihenfolge ihres ersten Abgangs; Tag → Gruppe über ein Verzeichnis (kein Suchen je Abgang)
        var groups: [(day: LocalDay, idx: [Int])] = []
        var groupOf: [LocalDay: Int] = [:]
        for i in disposeIdx {
            let d = day(steps[i].time, zone)
            if let k = groupOf[d] {
                groups[k].idx.append(i)
            } else {
                groupOf[d] = groups.count
                groups.append((d, [i]))
            }
        }
        // 1. Gleicher Tag: die Gruppe gegen die Zugänge des Tags
        for g in groups {
            guard var pool = dayPools[g.day] else { continue }
            split(g.idx, min(openOf(g.idx), pool.held)) { i, want in
                let taken = pool.take(want)
                remaining[i] = (remaining[i] ?? 0) - taken.removed
                pieces[i, default: []] += piecesOf(taken, match: .SAME_DAY, matchedTime: dayFirst[g.day])
            }
            dayPools[g.day] = pool
        }
        // 2. 30 Tage: früheste Gruppe zuerst, Zugänge der 30 Tage danach früheste zuerst
        for g in groups {
            let to = g.day.plusDays(TaxTemplate.ukBbDays)
            // Erster Zugangstag nach dem Tag der Gruppe per binärer Suche, dann nur die Tage bis `to` — wie `subMap` (Android)
            var lo = 0
            var hi = poolDays.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if g.day < poolDays[mid] { hi = mid } else { lo = mid + 1 }
            }
            for ad in poolDays[lo...] {
                if to < ad { break }
                let r = openOf(g.idx)
                if r <= 0 { break }
                guard var pool = dayPools[ad], pool.held > 0 else { continue }
                split(g.idx, min(r, pool.held)) { i, want in
                    let taken = pool.take(want)
                    remaining[i] = (remaining[i] ?? 0) - taken.removed
                    pieces[i, default: []] += piecesOf(taken, match: .THIRTY_DAY, matchedTime: dayFirst[ad])
                }
                dayPools[ad] = pool
            }
        }
        // Der Pool-Teil einer Gruppe beim letzten Abgang des Tags (alle Buchungen bis dahin im Pool)
        var groupAt: [Int: [Int]] = [:]
        for g in groups { if let last = g.idx.last { groupAt[last] = g.idx } }
        // Auszahlung zwischen zwei Abgängen derselben Gruppe: erst nach deren Pool-Teil gebucht (die Abgänge des Tags sind
        // EINE Veräusserung und werden zuerst zugeordnet) — sonst leerte sie den Pool vor dem früheren Abgang. Wie Android.
        var deferred: [Int: [Int]] = [:]
        for (i, s) in steps.enumerated() where s.kind == .remove {
            guard let k = groupOf[day(s.time, zone)], let first = groups[k].idx.first, let last = groups[k].idx.last,
                  first < i, i < last else { continue }
            deferred[last, default: []].append(i)
        }
        let deferredSteps = Set(deferred.values.joined())
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
            let taken = dp.take(PortfolioDecimal.divide(dp.held, q, by: left))
            dayPools[d] = dp
            pool.addCarried(taken.removedPriced, from: taken.parts, est: taken.estimated)
            pool.add(taken.removedUnpriced, cost: nil, time: dayFirst[d] ?? 0)
        }
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                let d = day(s.acquired, zone)
                if late(s) {
                    pool.add(s.qty, cost: s.cost, time: s.acquired, est: s.costEstimated)
                } else if moved(s) {
                    enter(d, s.qty)
                } else if added.insert(d).inserted {
                    enter(d, dayOwn[d] ?? 0)
                }
            case .dispose:
                guard let group = groupAt[i] else { break }
                split(group, min(openOf(group), pool.held)) { m, want in
                    let taken = pool.take(want)
                    remaining[m] = (remaining[m] ?? 0) - taken.removed
                    pieces[m, default: []] += piecesOf(taken, match: .POOL)
                }
                for r in deferred[i] ?? [] { _ = pool.take(steps[r].qty) }
                for m in group {
                    var list = pieces[m] ?? []
                    let rest = remaining[m] ?? 0
                    if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                    // Erlös vor Gebühren mitführen: die Meldegrenze an HMRC prüft die Erlöse vor Abzug der Kosten
                    out += emit(coin, steps[m], list, proceeds: steps[m].proceeds, gross: steps[m].gross)
                }
            case .remove:
                if !deferredSteps.contains(i) { _ = pool.take(s.qty) }
            case .networkFee:
                note(&lost, coin, s, pool.dropQty(s.qty))
            case .addFee:
                noteUnassigned(&unassigned, coin, s, pool.addFee(s.time, s.cost ?? 0, amount: s.trade.amountExact,
                                                                 coinFee: s.trade.coinFeeExact, est: s.costEstimated))
            }
        }
        return out
    }
}
