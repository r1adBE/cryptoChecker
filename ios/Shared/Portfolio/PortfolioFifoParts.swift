import Foundation
import os

// Herkunftsteile der FIFO-Posten (Teil von `PortfolioFifo`) ohne Teil je Posten und Umbuchung — wie
// `PortfolioFifoParts.kt` (Android). Die Gebühr USDT (+ Wert der Gebühr im dritten Coin) einer Umbuchung verteilt sich
// nach Menge auf alle Posten der Warteschlange: statt jedem Posten einen Teil zuzuschlagen, merkt sich die Warteschlange
// nur den Anteil je Coin (`FeeStream`), und ein Posten mit Kaufpreis trägt aus jeder Umbuchung, während der er dort lag,
// seine Menge × Anteil — gerechnet erst beim Auslesen. Das gilt auch nach Abgängen: ein Abgang senkt Menge und Teile im
// selben Verhältnis. Abgegebene Teile sind Einträge (`Entry`: fester Betrag oder Bereich des Stroms mit Gewicht),
// ausgelesen erst, wenn jemand die Teile einer Veräusserung braucht (`PortfolioFifoParts`).

extension PortfolioFifo {
    /// Gebühren der Umbuchungen einer Warteschlange: je Umbuchung Zeitpunkt, Anteil je Coin (Gebühr ÷ Bestand, 34
    /// Stellen) und laufende Summe der Anteile. Nur Anhängen.
    final class FeeStream: Sendable {
        private struct State {
            var times: [Int64] = []
            var factors: [Decimal] = []
            var prefix: [Decimal] = [0]
        }

        // Angehängt nur während `run` (ein Faden), danach nur gelesen; die Sperre macht den Typ sicher teilbar
        private let state = OSAllocatedUnfairLock(initialState: State())

        var count: Int { state.withLock { $0.times.count } }

        func append(_ time: Int64, _ factor: Decimal) {
            state.withLock { s in
                s.times.append(time)
                s.factors.append(factor)
                s.prefix.append(s.prefix[s.prefix.count - 1] + factor)
            }
        }

        /// Σ Anteile der Umbuchungen `from` bis vor `to`.
        func sum(_ from: Int, _ to: Int) -> Decimal { state.withLock { $0.prefix[to] - $0.prefix[from] } }

        func time(_ k: Int) -> Int64 { state.withLock { $0.times[k] } }

        func factor(_ k: Int) -> Decimal { state.withLock { $0.factors[k] } }
    }

    /// Teil eines Einstands: fester Betrag zu einem Zeitpunkt oder die Umbuchungen `from..<to` eines Stroms mit Gewicht
    /// (Menge) — je Umbuchung k ein Teil Gewicht × Anteil k zu ihrem Zeitpunkt.
    enum Entry: Sendable {
        case point(time: Int64, usdt: Decimal)
        case range(stream: FeeStream, from: Int, to: Int, weight: Decimal)

        /// Betrag (Bereich: Gewicht × Σ Anteile, 34 Stellen).
        var value: Decimal {
            switch self {
            case .point(_, let usdt): return usdt
            case .range(let stream, let from, let to, let weight):
                return PortfolioDecimal.multiply(weight, stream.sum(from, to))
            }
        }

        /// Die Teile einzeln (Bereich: je Umbuchung einer).
        func forEachPart(_ body: (Int64, Decimal) -> Void) {
            switch self {
            case .point(let time, let usdt): body(time, usdt)
            case .range(let stream, let from, let to, let weight):
                for k in from..<to { body(stream.time(k), PortfolioDecimal.multiply(weight, stream.factor(k))) }
            }
        }
    }

    /// Eigene Teile eines Postens (bzw. wartender Gebühr-Einstand): feste Beträge je Zeitpunkt zusammengefasst (gleicher
    /// Devisenkurs), Bereiche je Strom und Grenzen.
    struct Entries {
        private struct RangeKey: Hashable {
            let stream: ObjectIdentifier
            let from: Int
            let to: Int
        }

        private(set) var items: [Entry] = []
        private var points: [Int64: Int] = [:]
        private var ranges: [RangeKey: Int] = [:]

        init() {}

        /// Feste Beträge in dieser Reihenfolge, auch 0 (wie eine Liste nach dem anteiligen Herausnehmen).
        init(points parts: [PortfolioCostPart]) {
            for p in parts {
                points[p.time] = items.count
                items.append(.point(time: p.time, usdt: p.usdt))
            }
        }

        var isEmpty: Bool { items.isEmpty }

        /// Summe der Beträge.
        var value: Decimal { items.reduce(Decimal(0)) { $0 + $1.value } }

        /// Nur feste Beträge als Liste; nil, wenn ein Bereich dabei ist.
        var pointParts: [PortfolioCostPart]? {
            var out: [PortfolioCostPart] = []
            for e in items {
                guard case .point(let time, let usdt) = e else { return nil }
                out.append(PortfolioCostPart(time: time, usdt: usdt))
            }
            return out
        }

        /// Fügt `entry` hinzu (0 = nichts): gleicher Zeitpunkt bzw. gleicher Bereich wird addiert.
        mutating func add(_ entry: Entry) {
            switch entry {
            case .point(let time, let usdt):
                guard usdt != 0 else { return }
                if let i = points[time], case .point(_, let old) = items[i] {
                    items[i] = .point(time: time, usdt: old + usdt)
                } else {
                    points[time] = items.count
                    items.append(entry)
                }
            case .range(let stream, let from, let to, let weight):
                guard to > from, weight != 0 else { return }
                let key = RangeKey(stream: ObjectIdentifier(stream), from: from, to: to)
                if let i = ranges[key], case .range(_, _, _, let old) = items[i] {
                    items[i] = .range(stream: stream, from: from, to: to, weight: old + weight)
                } else {
                    ranges[key] = items.count
                    items.append(entry)
                }
            }
        }

        mutating func removeAll() {
            items.removeAll()
            points.removeAll()
            ranges.removeAll()
        }
    }

    /// Teile von `entries` mit Summe exakt `total`: gleiche Zeitpunkte zusammengefasst (Reihenfolge des ersten
    /// Auftretens), der letzte Teil ist der Rest.
    static func parts(_ entries: [Entry], total: Decimal) -> [PortfolioCostPart] {
        var index: [Int64: Int] = [:]
        var out: [PortfolioCostPart] = []
        for e in entries {
            e.forEachPart { time, usdt in
                if let i = index[time] {
                    out[i] = PortfolioCostPart(time: time, usdt: out[i].usdt + usdt)
                } else {
                    index[time] = out.count
                    out.append(PortfolioCostPart(time: time, usdt: usdt))
                }
            }
        }
        guard let last = out.last else { return [] }
        let others = out.dropLast().reduce(Decimal(0)) { $0 + $1.usdt }
        out[out.count - 1] = PortfolioCostPart(time: last.time, usdt: total - others)
        return out
    }

    /// Offene Herkunftsteile aller Posten mit Kaufpreis (Summe exakt `total`): die eigenen Teile je Posten, dazu je
    /// Umbuchung EIN Teil aus der Menge aller Posten, die da schon in der Warteschlange lagen (Menge × Anteil).
    static func openParts(_ queues: [Queue], total: Decimal) -> [PortfolioCostPart] {
        var out: [PortfolioCostPart] = []
        for q in queues {
            var since: [(from: Int, amount: Decimal)] = []
            for lot in q.lots where lot.priced {
                out += lot.ownParts
                if let from = lot.openFrom { since.append((from, lot.amount)) }
            }
            guard !since.isEmpty else { continue }
            since.sort { $0.from < $1.from }
            var amount: Decimal = 0
            var next = 0
            for k in since[0].from..<q.stream.count {
                while next < since.count && since[next].from <= k {
                    amount += since[next].amount
                    next += 1
                }
                out.append(PortfolioCostPart(time: q.stream.time(k),
                                             usdt: PortfolioDecimal.multiply(amount, q.stream.factor(k))))
            }
        }
        guard let last = out.last else { return [] }
        let others = out.dropLast().reduce(Decimal(0)) { $0 + $1.usdt }
        out[out.count - 1] = PortfolioCostPart(time: last.time, usdt: total - others)
        return out
    }
}

/// Herkunftsteile einer FIFO-Veräusserung (`PortfolioDisposal.costParts`): Einträge mit Summe `total`, ausgelesen beim
/// ersten Zugriff (`PortfolioFifo.parts`), dann behalten. Wie `PortfolioFifoParts` (Android).
final class PortfolioFifoParts: Sendable {
    private let entries: [PortfolioFifo.Entry]
    private let total: Decimal
    private let cached = OSAllocatedUnfairLock<[PortfolioCostPart]?>(initialState: nil)

    init(entries: [PortfolioFifo.Entry], total: Decimal) {
        self.entries = entries
        self.total = total
    }

    var items: [PortfolioCostPart] {
        cached.withLock { cached in
            if let cached { return cached }
            let parts = PortfolioFifo.parts(entries, total: total)
            cached = parts
            return parts
        }
    }
}
