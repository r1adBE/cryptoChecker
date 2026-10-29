import Foundation
import os

/// Speicher eines `PortfolioCostPartPool` (nur Anhängen, damit Ansichten gültig bleiben): Teile (Zeitpunkt, Zugänge
/// verkettet), Zugänge (Betrag, Zahl der Abgänge davor) und die Anteile der Abgänge als Baum — Ebene 0 je Abgang,
/// Ebene h das Produkt von 2^h aufeinanderfolgenden (auf 34 Stellen). Wie `PortfolioPartStore` (Android).
///
/// Vom Compiler geprüft `Sendable`, weil abgegebene Teile (`PortfolioScaledParts` in einer `PortfolioCostPartList`) den
/// Speicher über Aufgaben hinweg lesen dürfen: aller Zustand liegt in `state` (`OSAllocatedUnfairLock`), jeder Zugriff
/// — lesend wie ändernd — geht über die Sperre. Geschrieben wird weiterhin nur aus EINER Rechnung je Speicher (heute je
/// Pool lokal in einer Rechnung, `PortfolioCalculatorAverage`, `TaxEngineCore`). `weightedSum` und `anyTime` rufen die
/// Gewichte bzw. Prüfungen des Aufrufers (nicht `Sendable`) ausserhalb von `state`, aber unter `lookup` auf — je ein
/// Schritt wie bisher; was sie brauchen, holen sie vorher unter `state` heraus.
final class PortfolioPartStore: Sendable {
    /// Umfang: Teile, Zugänge, Abgänge.
    struct Mark: Equatable, Sendable {
        var slots = 0
        var contribs = 0
        var takes = 0
    }

    private let state: OSAllocatedUnfairLock<PortfolioPartState>
    /// Hält je einen Aufruf von `weightedSum` bzw. `anyTime` zusammen (Lesen, Gewichte rechnen, Nachführen).
    private let lookup = NSLock()

    init() {
        state = OSAllocatedUnfairLock(initialState: PortfolioPartState())
    }

    private init(state: PortfolioPartState) {
        self.state = OSAllocatedUnfairLock(initialState: state)
    }

    var slotCount: Int { state.withLock { $0.slotTime.count } }
    var mark: Mark { state.withLock { $0.mark } }

    func newSlot(_ time: Int64, _ usdt: Decimal) {
        state.withLock { $0.newSlot(time, usdt) }
    }

    func contribute(_ slot: Int, _ usdt: Decimal) {
        state.withLock { $0.contribute(slot, usdt) }
    }

    /// Abgang: es bleibt `remaining` von `total`.
    func scale(_ remaining: Decimal, total: Decimal) {
        let share = PortfolioDecimal.divide(remaining, total)
        state.withLock { $0.push(share) }
    }

    /// Eigener Speicher mit dem Umfang `mark` (eine Kopie des Pools ändert, während eine andere den Speicher schon
    /// weitergeführt hat).
    func copy(upTo mark: Mark) -> PortfolioPartStore {
        PortfolioPartStore(state: state.withLock { $0.copy(upTo: mark) })
    }

    func view(_ mark: Mark, removed: Decimal, total: Decimal) -> PortfolioCostPartList {
        PortfolioCostPartList(view: PortfolioScaledParts(store: self, len: mark.slots, takes: mark.takes,
                                                         seq: mark.contribs, removed: removed, total: total))
    }

    /// Teile einer Ansicht (`PortfolioScaledParts`); die Produkte von hinten nach vorn, je Bereich einmal.
    func read(len: Int, takes k: Int, seq: Int, removed: Decimal, total: Decimal) -> [PortfolioCostPart] {
        state.withLock { $0.read(len: len, takes: k, seq: seq, removed: removed, total: total) }
    }

    func timeBounds(_ len: Int) -> [Int64] {
        state.withLock { [$0.slotMin[len - 1], $0.slotMax[len - 1]] }
    }

    // MARK: Laufende Summe je Gewicht (`weightedSum`) und erster Treffer je Prüfung (`anyTime`), beim Lesen nachgeführt

    /// Σ Teil × Gewicht einer Ansicht: laufende Summe Σ Zugang × Gewicht seines Teils, bei jedem Abgang mit dessen
    /// Anteil multipliziert, zum Zeitpunkt der Ansicht × `removed` ÷ `total`. Die Gewichte der dabei neu erreichten
    /// Teile werden vorab bestimmt (dieselben Zeitpunkte in derselben Reihenfolge wie beim Nachführen).
    func weightedSum(_ view: PortfolioScaledParts, _ weight: PortfolioPartWeight) -> Decimal? {
        let weightId = weight.id
        let takes = view.takes
        return lookup.withLock {
            let plan = state.withLock { $0.planWeights(weightId, takes: takes) }
            let weights = plan.times.map { weight.of($0) }
            return state.withLock { s -> Decimal? in
                let at = s.advanceRunning(weights, plan: plan, takes: takes)
                if s.missingFrom < view.seq { return nil }
                return view.removed == view.total ? at : PortfolioDecimal.divide(at, view.removed, by: view.total)
            }
        }
    }

    func anyTime(_ view: PortfolioScaledParts, _ test: PortfolioPartTest) -> Bool {
        let testId = test.id
        let len = view.len
        return lookup.withLock {
            let pending = state.withLock { $0.pendingTestTimes(testId, len: len) }
            var scanned = pending.start
            var first: Int?
            for time in pending.times {
                let hit = test.of(time)
                scanned += 1
                if hit {
                    first = scanned - 1
                    break
                }
            }
            let found = first
            let done = scanned
            return state.withLock { s -> Bool in
                if !pending.times.isEmpty {
                    s.testScanned = done
                    if let found { s.testFirst = found }
                }
                return s.testFirst < len
            }
        }
    }
}

/// Zustand eines `PortfolioPartStore` (nur unter dessen Sperre).
private struct PortfolioPartState: Sendable {
    var slotTime: [Int64] = []
    var slotMin: [Int64] = []
    var slotMax: [Int64] = []
    var slotHead: [Int] = []
    var slotTail: [Int] = []
    var cUsdt: [Decimal] = []
    var cTakes: [Int] = []
    var cSlot: [Int] = []
    var cNext: [Int] = []
    var levels: [[Decimal]] = []

    /// Nummer des Gewichts der laufenden Summe (`PortfolioPartWeight.id`); nil = noch keins.
    var weightFor: UInt64?
    var slotWeight: [Decimal?] = []
    var running: Decimal = 0
    var runningContribs = 0
    /// Laufende Summe vor Abgang t (nach allen Zugängen davor).
    var beforeTake: [Decimal] = []
    /// Erster Zugang mit fehlendem Gewicht (Index); `Int.max` = keiner bisher.
    var missingFrom = Int.max
    /// Nummer der Prüfung (`PortfolioPartTest.id`); nil = noch keine.
    var testFor: UInt64?
    var testScanned = 0
    var testFirst = Int.max

    /// Was `weightedSum` nachführt: bis Abgang `takesEnd` (ausschliesslich), Zugänge bis `contribsEnd`; `times` =
    /// Zeitpunkte der Teile, deren Gewicht dafür noch fehlt, in der Reihenfolge des Nachführens.
    struct WeightPlan: Sendable {
        let takesEnd: Int
        let contribsEnd: Int
        let times: [Int64]
    }

    private var takeCount: Int { levels.first?.count ?? 0 }
    var mark: PortfolioPartStore.Mark {
        PortfolioPartStore.Mark(slots: slotTime.count, contribs: cUsdt.count, takes: takeCount)
    }

    mutating func newSlot(_ time: Int64, _ usdt: Decimal) {
        let n = slotTime.count
        slotTime.append(time)
        slotMin.append(n == 0 ? time : min(slotMin[n - 1], time))
        slotMax.append(n == 0 ? time : max(slotMax[n - 1], time))
        slotHead.append(cUsdt.count)
        slotTail.append(cUsdt.count)
        append(n, usdt)
    }

    mutating func contribute(_ slot: Int, _ usdt: Decimal) {
        cNext[slotTail[slot]] = cUsdt.count
        slotTail[slot] = cUsdt.count
        append(slot, usdt)
    }

    private mutating func append(_ slot: Int, _ usdt: Decimal) {
        cUsdt.append(usdt)
        cTakes.append(takeCount)
        cSlot.append(slot)
        cNext.append(-1)
    }

    mutating func push(_ share: Decimal) {
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

    func copy(upTo mark: PortfolioPartStore.Mark) -> PortfolioPartState {
        var out = PortfolioPartState()
        var t = 0
        for c in 0..<mark.contribs {
            while t < cTakes[c] {
                out.push(levels[0][t])
                t += 1
            }
            if cSlot[c] == out.slotTime.count {
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

    /// Erster Schritt von `weightedSum`: bei einem anderen Gewicht von vorn; dann, ohne etwas zu ändern, wie weit die
    /// laufende Summe für eine Ansicht nach `takes` Abgängen nachgeführt wird und welche Gewichte dafür fehlen.
    mutating func planWeights(_ weightId: UInt64, takes: Int) -> WeightPlan {
        if weightFor != weightId {
            weightFor = weightId
            slotWeight = []
            running = 0
            runningContribs = 0
            beforeTake = []
            missingFrom = Int.max
        }
        var t = beforeTake.count
        var c = runningContribs
        while t <= takes && t < takeCount {
            while c < cUsdt.count && cTakes[c] <= t { c += 1 }
            t += 1
        }
        if takes >= t { c = cUsdt.count }
        let needed = cSlot[runningContribs..<c].max().map { $0 + 1 } ?? 0
        let times = slotWeight.count < needed ? Array(slotTime[slotWeight.count..<needed]) : []
        return WeightPlan(takesEnd: t, contribsEnd: c, times: times)
    }

    /// Zweiter Schritt von `weightedSum`: die vorab bestimmten Gewichte übernehmen und die laufende Summe wie geplant
    /// nachführen; Rückgabe = Summe zum Zeitpunkt der Ansicht (vor `removed` ÷ `total`).
    mutating func advanceRunning(_ weights: [Decimal?], plan: WeightPlan, takes: Int) -> Decimal {
        slotWeight.append(contentsOf: weights)
        while beforeTake.count < plan.takesEnd {
            let t = beforeTake.count
            while runningContribs < plan.contribsEnd && cTakes[runningContribs] <= t {
                addRunning(runningContribs)
                runningContribs += 1
            }
            beforeTake.append(running)
            running = PortfolioDecimal.multiply(running, levels[0][t])
        }
        if takes < beforeTake.count { return beforeTake[takes] }
        while runningContribs < plan.contribsEnd {
            addRunning(runningContribs)
            runningContribs += 1
        }
        return running
    }

    private mutating func addRunning(_ c: Int) {
        if let w = slotWeight[cSlot[c]] {
            running += PortfolioDecimal.multiply(cUsdt[c], w)
        } else if c < missingFrom {
            missingFrom = c
        }
    }

    /// Erster Schritt von `anyTime`: bei einer anderen Prüfung von vorn; dann die Zeitpunkte, die für eine Ansicht mit
    /// `len` Teilen noch zu prüfen sind (leer, wenn schon ein Treffer feststeht oder alle geprüft sind), ab `start`.
    mutating func pendingTestTimes(_ testId: UInt64, len: Int) -> (start: Int, times: [Int64]) {
        if testFor != testId {
            testFor = testId
            testScanned = 0
            testFirst = Int.max
        }
        guard testFirst == Int.max && testScanned < len else { return (testScanned, []) }
        return (testScanned, Array(slotTime[testScanned..<len]))
    }
}
