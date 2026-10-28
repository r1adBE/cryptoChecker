import Foundation

/// Speicher eines `PortfolioCostPartPool` (nur Anhängen, damit Ansichten gültig bleiben): Teile (Zeitpunkt, Zugänge
/// verkettet), Zugänge (Betrag, Zahl der Abgänge davor) und die Anteile der Abgänge als Baum — Ebene 0 je Abgang,
/// Ebene h das Produkt von 2^h aufeinanderfolgenden (auf 34 Stellen). Wie `PortfolioPartStore` (Android).
final class PortfolioPartStore: @unchecked Sendable {
    /// Umfang: Teile, Zugänge, Abgänge.
    struct Mark: Equatable, Sendable {
        var slots = 0
        var contribs = 0
        var takes = 0
    }

    private var slotTime: [Int64] = []
    private var slotMin: [Int64] = []
    private var slotMax: [Int64] = []
    private var slotHead: [Int] = []
    private var slotTail: [Int] = []
    private var cUsdt: [Decimal] = []
    private var cTakes: [Int] = []
    private var cSlot: [Int] = []
    private var cNext: [Int] = []
    private var levels: [[Decimal]] = []
    private let lock = NSLock()

    var slotCount: Int { slotTime.count }
    private var takeCount: Int { levels.first?.count ?? 0 }
    var mark: Mark { Mark(slots: slotTime.count, contribs: cUsdt.count, takes: takeCount) }

    func newSlot(_ time: Int64, _ usdt: Decimal) {
        let n = slotTime.count
        slotTime.append(time)
        slotMin.append(n == 0 ? time : min(slotMin[n - 1], time))
        slotMax.append(n == 0 ? time : max(slotMax[n - 1], time))
        slotHead.append(cUsdt.count)
        slotTail.append(cUsdt.count)
        append(n, usdt)
    }

    func contribute(_ slot: Int, _ usdt: Decimal) {
        cNext[slotTail[slot]] = cUsdt.count
        slotTail[slot] = cUsdt.count
        append(slot, usdt)
    }

    private func append(_ slot: Int, _ usdt: Decimal) {
        cUsdt.append(usdt)
        cTakes.append(takeCount)
        cSlot.append(slot)
        cNext.append(-1)
    }

    /// Abgang: es bleibt `remaining` von `total`.
    func scale(_ remaining: Decimal, total: Decimal) {
        push(PortfolioDecimal.divide(remaining, total))
    }

    private func push(_ share: Decimal) {
        var v = share
        var h = 0
        while true {
            if levels.count == h { levels.append([]) }
            levels[h].append(v)
            let i = levels[h].count - 1
            if i & 1 == 0 { return }
            v = PortfolioDecimal.multiply(levels[h][i - 1], v)
            h += 1
        }
    }

    /// Eigener Speicher mit dem Umfang `mark` (eine Kopie des Pools ändert, während eine andere den Speicher schon
    /// weitergeführt hat).
    func copy(upTo mark: Mark) -> PortfolioPartStore {
        let out = PortfolioPartStore()
        var t = 0
        for c in 0..<mark.contribs {
            while t < cTakes[c] {
                out.push(levels[0][t])
                t += 1
            }
            if cSlot[c] == out.slotCount {
                out.newSlot(slotTime[cSlot[c]], cUsdt[c])
            } else {
                out.contribute(cSlot[c], cUsdt[c])
            }
        }
        while t < mark.takes {
            out.push(levels[0][t])
            t += 1
        }
        return out
    }

    func view(_ mark: Mark, removed: Decimal, total: Decimal) -> PortfolioCostPartList {
        PortfolioCostPartList(view: PortfolioScaledParts(store: self, len: mark.slots, takes: mark.takes,
                                                         seq: mark.contribs, removed: removed, total: total))
    }

    /// Produkt der Anteile der Abgänge `from` bis vor `to` (Knoten des Baums); nil = keiner (Faktor 1).
    private func range(_ from: Int, _ to: Int) -> Decimal? {
        var l = from
        var r = to
        var h = 0
        var left: Decimal?
        var right: Decimal?
        while l < r {
            if l & 1 == 1 {
                let x = levels[h][l]
                left = left.map { PortfolioDecimal.multiply($0, x) } ?? x
                l += 1
            }
            if r & 1 == 1 {
                r -= 1
                let x = levels[h][r]
                right = right.map { PortfolioDecimal.multiply(x, $0) } ?? x
            }
            l >>= 1
            r >>= 1
            h += 1
        }
        guard let left else { return right }
        guard let right else { return left }
        return PortfolioDecimal.multiply(left, right)
    }

    /// Teile einer Ansicht (`PortfolioScaledParts`); die Produkte von hinten nach vorn, je Bereich einmal.
    func read(len: Int, takes k: Int, seq: Int, removed: Decimal, total: Decimal) -> [PortfolioCostPart] {
        guard len > 0 else { return [] }
        var values = [Decimal](repeating: 0, count: len)
        var acc: Decimal = 1 // Produkt der Abgänge [low, k)
        var low = k
        var chain: [Int] = []
        // Der letzte Teil ist der Rest: nur die übrigen brauchen ihren Wert
        var i = len - 2
        while i >= 0 {
            chain.removeAll(keepingCapacity: true)
            var c = slotHead[i]
            while c >= 0 && c < seq {
                chain.append(c)
                c = cNext[c]
            }
            var sum: Decimal?
            for x in chain.reversed() {
                let from = cTakes[x]
                let usdt = cUsdt[x]
                let v: Decimal
                if from >= k {
                    v = usdt
                } else if from < low {
                    let p = range(from, low) ?? 1
                    acc = low == k ? p : PortfolioDecimal.multiply(p, acc)
                    low = from
                    v = PortfolioDecimal.multiply(usdt, acc)
                } else if from == low {
                    v = PortfolioDecimal.multiply(usdt, acc)
                } else {
                    v = PortfolioDecimal.multiply(usdt, range(from, k) ?? 1)
                }
                sum = sum.map { v + $0 } ?? v
            }
            values[i] = sum ?? 0
            i -= 1
        }
        let all = removed == total
        var out: [PortfolioCostPart] = []
        out.reserveCapacity(len)
        var left = removed
        for i in 0..<len {
            let r = i == len - 1 ? left : (all ? values[i] : PortfolioDecimal.divide(values[i], removed, by: total))
            left -= r
            out.append(PortfolioCostPart(time: slotTime[i], usdt: r))
        }
        return out
    }

    func timeBounds(_ len: Int) -> [Int64] { [slotMin[len - 1], slotMax[len - 1]] }

    // MARK: Laufende Summe je Gewicht (`weightedSum`) und erster Treffer je Prüfung (`anyTime`), beim Lesen nachgeführt

    private var weightFor: PortfolioPartWeight?
    private var slotWeight: [Decimal?] = []
    private var running: Decimal = 0
    private var runningContribs = 0
    /// Laufende Summe vor Abgang t (nach allen Zugängen davor).
    private var beforeTake: [Decimal] = []
    /// Erster Zugang mit fehlendem Gewicht (Index); `Int.max` = keiner bisher.
    private var missingFrom = Int.max
    private var testFor: PortfolioPartTest?
    private var testScanned = 0
    private var testFirst = Int.max

    /// Σ Teil × Gewicht einer Ansicht: laufende Summe Σ Zugang × Gewicht seines Teils, bei jedem Abgang mit dessen
    /// Anteil multipliziert, zum Zeitpunkt der Ansicht × `removed` ÷ `total`.
    func weightedSum(_ view: PortfolioScaledParts, _ weight: PortfolioPartWeight) -> Decimal? {
        lock.withLock {
            if weightFor !== weight {
                weightFor = weight
                slotWeight = []
                running = 0
                runningContribs = 0
                beforeTake = []
                missingFrom = Int.max
            }
            while beforeTake.count <= view.takes && beforeTake.count < takeCount {
                let t = beforeTake.count
                while runningContribs < cUsdt.count && cTakes[runningContribs] <= t {
                    addRunning(runningContribs, weight)
                    runningContribs += 1
                }
                beforeTake.append(running)
                running = PortfolioDecimal.multiply(running, levels[0][t])
            }
            let at: Decimal
            if view.takes < beforeTake.count {
                at = beforeTake[view.takes]
            } else {
                while runningContribs < cUsdt.count {
                    addRunning(runningContribs, weight)
                    runningContribs += 1
                }
                at = running
            }
            if missingFrom < view.seq { return nil }
            return view.removed == view.total ? at : PortfolioDecimal.divide(at, view.removed, by: view.total)
        }
    }

    private func addRunning(_ c: Int, _ weight: PortfolioPartWeight) {
        let slot = cSlot[c]
        while slotWeight.count <= slot { slotWeight.append(weight.of(slotTime[slotWeight.count])) }
        if let w = slotWeight[slot] {
            running += PortfolioDecimal.multiply(cUsdt[c], w)
        } else if c < missingFrom {
            missingFrom = c
        }
    }

    func anyTime(_ view: PortfolioScaledParts, _ test: PortfolioPartTest) -> Bool {
        lock.withLock {
            if testFor !== test {
                testFor = test
                testScanned = 0
                testFirst = Int.max
            }
            while testFirst == Int.max && testScanned < view.len {
                if test.of(slotTime[testScanned]) { testFirst = testScanned }
                testScanned += 1
            }
            return testFirst < view.len
        }
    }
}
