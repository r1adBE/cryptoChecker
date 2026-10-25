import Foundation

// Posten, Warteschlangen und verbrauchte Teile der FIFO-Rechnung (Teil von `PortfolioFifo`) — wie
// `PortfolioFifoLots.kt` (Android). Herkunftsteile: PortfolioFifoParts.swift.

extension PortfolioFifo {
    private static let dayMillis: Int64 = 86_400_000

    /// Posten: Menge und Einstand samt Herkunft. Einstand = eigene Teile (`own`: Erwerb, zugeschlagene und übernommene
    /// Beträge) + Menge × Σ Anteile der Umbuchungen seit dem Eintritt in die Warteschlange (`FeeStream`).
    final class Lot {
        let txId: Int64
        /// Erwerb (Reihenfolge, Haltedauer).
        let acquired: Int64
        /// Zeit der Transaktion (bei gleichem Erwerb).
        let time: Int64
        let kind: PortfolioTxType
        var amount: Decimal
        /// Kaufpreis bekannt (sonst kein Einstand und keine Teile).
        let priced: Bool
        /// Eigene Teile (ohne die Umbuchungen seit `from`).
        private(set) var own = Entries()
        /// Summe von `own`.
        private var ownCost: Decimal = 0
        /// Gebühren-Strom der Warteschlange und erste Umbuchung, an der der Posten teilnimmt.
        private var stream: FeeStream?
        private var from = 0

        /// `entries` nil = ganzer Einstand zum Erwerb.
        init(txId: Int64, acquired: Int64, time: Int64, kind: PortfolioTxType, amount: Decimal, cost: Decimal?,
             entries: [Entry]? = nil) {
            self.txId = txId
            self.acquired = acquired
            self.time = time
            self.kind = kind
            self.amount = amount
            priced = cost != nil
            guard let cost else { return }
            if let entries {
                for e in entries { own.add(e) }
                ownCost = own.value
            } else {
                own.add(.point(time: acquired, usdt: cost))
                ownCost = cost
            }
        }

        /// In eine Warteschlange aufgenommen: nimmt an deren folgenden Umbuchungen teil.
        func join(_ stream: FeeStream) {
            self.stream = stream
            from = stream.count
        }

        /// Σ Anteile der Umbuchungen seit dem Eintritt (0 = keine).
        private var openSum: Decimal {
            guard let stream, stream.count > from else { return 0 }
            return stream.sum(from, stream.count)
        }

        /// Erste Umbuchung, an der der Posten teilnimmt; nil = keine seit dem Eintritt.
        var openFrom: Int? {
            guard let stream, stream.count > from else { return nil }
            return from
        }

        /// Rest-Einstand; nil = Kaufpreis unbekannt.
        var cost: Decimal? {
            guard priced else { return nil }
            let open = openSum
            return open == 0 ? ownCost : ownCost + PortfolioDecimal.multiply(amount, open)
        }

        /// Eigene Teile (Summe exakt = eigener Einstand).
        var ownParts: [PortfolioCostPart] { PortfolioFifo.parts(own.items, total: ownCost) }

        /// Alle Teile (Summe exakt = Einstand); leer ohne Kaufpreis.
        var parts: [PortfolioCostPart] { PortfolioFifo.parts(allEntries(amount), total: cost ?? 0) }

        /// Eigene Teile und die Umbuchungen seit dem Eintritt mit Gewicht `weight`.
        private func allEntries(_ weight: Decimal) -> [Entry] {
            guard let stream, stream.count > from else { return own.items }
            return own.items + [.range(stream: stream, from: from, to: stream.count, weight: weight)]
        }

        /// Gebühr bzw. übernommener Einstand `usdt` zum Zeitpunkt `at` (nur mit Kaufpreis).
        func addCost(_ at: Int64, _ usdt: Decimal) {
            guard priced, usdt != 0 else { return }
            own.add(.point(time: at, usdt: usdt))
            ownCost += usdt
        }

        /// Übernimmt Einstand samt Herkunft `entries` (nur mit Kaufpreis).
        func addEntries(_ entries: [Entry]) {
            guard priced, !entries.isEmpty else { return }
            // Summe mitführen statt alle eigenen Einträge neu zu addieren (der älteste Posten sammelt viele)
            for e in entries {
                own.add(e)
                ownCost += e.value
            }
        }

        /// Herausgenommener Einstand samt Herkunft.
        struct Taken {
            let cost: Decimal
            let entries: [Entry]
        }

        /// Einstand der Teilmenge `qty` (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; nil ohne Kaufpreis.
        /// Die Umbuchungen seit dem Eintritt gehen mit Gewicht `qty` mit (ohne Teilung); nur feste Beträge: anteilig wie
        /// eine Liste (letzter Teil = Rest, `PortfolioCostParts.take`).
        func take(_ qty: Decimal) -> Taken? {
            guard let c = cost else { return nil }
            let removed = qty == amount ? c : PortfolioDecimal.divide(c * qty, amount)
            if qty == amount {
                let out = allEntries(amount)
                own.removeAll()
                ownCost = 0
                return Taken(cost: removed, entries: out)
            }
            if openFrom == nil, var parts = own.pointParts {
                let taken = PortfolioCostParts.take(&parts, total: c, target: removed)
                own = Entries(points: parts)
                ownCost = c - removed
                return Taken(cost: removed, entries: taken.map { .point(time: $0.time, usdt: $0.usdt) })
            }
            var out: [Entry] = []
            var rest = Entries()
            for e in own.items {
                switch e {
                case .point(let time, let usdt):
                    let part = c == 0 ? 0 : PortfolioDecimal.divide(usdt * removed, c)
                    out.append(.point(time: time, usdt: part))
                    rest.add(.point(time: time, usdt: usdt - part))
                case .range(let stream, let from, let to, let weight):
                    let part = PortfolioDecimal.divide(weight * qty, amount)
                    out.append(.range(stream: stream, from: from, to: to, weight: part))
                    rest.add(.range(stream: stream, from: from, to: to, weight: weight - part))
                }
            }
            if let stream, stream.count > from {
                out.append(.range(stream: stream, from: from, to: stream.count, weight: qty))
            }
            own = rest
            ownCost = own.value
            return Taken(cost: removed, entries: out)
        }

        /// Liegt vor `other`: Erwerb, dann Zeit, dann Id.
        func isBefore(_ other: Lot) -> Bool {
            if acquired != other.acquired { return acquired < other.acquired }
            if time != other.time { return time < other.time }
            return txId < other.txId
        }
    }

    /// Warteschlange einer Wallet (universell: eine einzige). Verbrauchte Posten vorn rücken nur den Kopf `head` weiter
    /// (kein Verschieben aller übrigen); Platz wird gelegentlich freigegeben. Reihenfolge wie eine einfache Liste.
    final class Queue {
        let key: String
        private var items: [Lot] = []
        private var head = 0
        var held: Decimal = 0 // Σ Menge der Posten
        /// Σ Menge der Posten mit Kaufpreis.
        private(set) var pricedHeld: Decimal = 0
        /// Zahl der Posten ohne Kaufpreis.
        private(set) var unpricedCount = 0
        /// Gebühren der Umbuchungen in dieser Warteschlange (nach Menge auf alle Posten).
        let stream = FeeStream()
        /// Offene Posten in FIFO-Reihenfolge.
        var lots: ArraySlice<Lot> { items[head...] }
        /// Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis.
        var pendingLost: [PortfolioLostFeeCost] = []
        /// Herkunft von `pendingLost` (Summe gleich).
        var pending = Entries()

        init(key: String) { self.key = key }

        /// Ältester Posten; nil = leer.
        var first: Lot? { head < items.count ? items[head] : nil }
        var isEmpty: Bool { head >= items.count }
        /// Alle Posten mit Kaufpreis.
        var allPriced: Bool { unpricedCount == 0 }
        /// Mindestens ein Posten mit Kaufpreis.
        var hasPriced: Bool { items.count - head > unpricedCount }

        /// Ältesten Posten entfernen (nur den Kopf weiterrücken; ab der Hälfte verbrauchter Plätze aufräumen).
        func removeFirst() {
            guard head < items.count else { return }
            if !items[head].priced { unpricedCount -= 1 }
            head += 1
            if head == items.count {
                items.removeAll(keepingCapacity: true)
                head = 0
            } else if head >= 64 && head * 2 >= items.count {
                items.removeFirst(head)
                head = 0
            }
        }

        /// `lot` an seinen Platz (meist hinten): hinter allen Posten, vor denen er nicht liegt (`Lot.isBefore`). Die
        /// Posten liegen immer in dieser Reihenfolge, darum binär gesucht — verschobene alte Posten (je Wallet) und
        /// Einzahlungen mit früherem Kaufdatum laufen nicht über alle jüngeren Posten.
        func insert(_ lot: Lot) {
            lot.join(stream)
            var low = head
            var high = items.count
            if high > low && !lot.isBefore(items[high - 1]) { low = high }
            while low < high {
                let mid = (low + high) / 2
                if lot.isBefore(items[mid]) { high = mid } else { low = mid + 1 }
            }
            items.insert(lot, at: low)
            held += lot.amount
            if lot.priced { pricedHeld += lot.amount } else { unpricedCount += 1 }
        }

        /// Menge `qty` von `lot` abgegangen (vor dem Entfernen).
        func reduce(_ lot: Lot, by qty: Decimal) {
            held -= qty
            if lot.priced { pricedHeld -= qty }
        }

        /// Leer: Bestände auf 0 (kein Rest aus Rundungen).
        func clearHeldIfEmpty() {
            guard isEmpty else { return }
            held = 0
            pricedHeld = 0
        }
    }

    /// Verbrauchter Teil eines Postens: Menge, Einstand (nil = ohne Kaufpreis) und dessen Herkunft.
    struct Slice {
        let txId: Int64
        let acquired: Int64
        let time: Int64
        let kind: PortfolioTxType
        let amount: Decimal
        let cost: Decimal?
        let entries: [Entry]
    }

    /// Summe der Einstände (ohne Kaufpreis = 0).
    static func costSum(_ slices: [Slice]) -> Decimal { slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) } }

    /// Gebühr `extra` einer Umbuchung zum Zeitpunkt `at` nach Menge auf alle Posten von `q` (Bestand `h`): ein Eintrag im
    /// Gebühren-Strom (Anteil je Coin), kein Teil je Posten. Haben alle Posten einen Kaufpreis, bekommt der letzte den
    /// Rundungsrest (Summe exakt `extra`). Rückgabe = Anteil der Posten OHNE Kaufpreis; 0 = alles zugeordnet.
    static func spreadQueue(_ q: Queue, _ extra: Decimal, _ h: Decimal, at: Int64) -> Decimal {
        guard extra > 0, h > 0 else { return 0 }
        let factor = PortfolioDecimal.divide(extra, h)
        q.stream.append(at, factor)
        guard q.allPriced else { return extra - PortfolioDecimal.multiply(q.pricedHeld, factor) }
        let rest = extra - PortfolioDecimal.multiply(h, factor)
        if rest != 0 { q.lots.last?.addCost(at, rest) }
        return 0
    }
}
