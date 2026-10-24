import Foundation

// Posten, Warteschlangen und verbrauchte Teile der FIFO-Rechnung (Teil von `PortfolioFifo`) — wie
// `PortfolioFifo.kt` (Android).

extension PortfolioFifo {
    private static let dayMillis: Int64 = 86_400_000

    final class Lot {
        let txId: Int64
        /// Erwerb (Reihenfolge, Haltedauer).
        let acquired: Int64
        /// Zeit der Transaktion (bei gleichem Erwerb).
        let time: Int64
        let kind: PortfolioTxType
        var amount: Decimal
        /// Rest-Einstand; nil = Kaufpreis unbekannt. Ändert sich nur zusammen mit `parts`.
        private(set) var cost: Decimal?
        /// Herkunft von `cost` (Summe = `cost`); leer ohne Kaufpreis bzw. bei Einstand 0.
        private(set) var parts: [PortfolioCostPart] = []
        /// Platz je Zeitpunkt in `parts` (`PortfolioCostParts.add` mit Index): gleicher Zeitpunkt ohne Suche.
        private var partIndex: [Int64: Int] = [:]

        /// `parts` nil = ganzer Einstand zum Erwerb.
        init(txId: Int64, acquired: Int64, time: Int64, kind: PortfolioTxType, amount: Decimal, cost: Decimal?,
             parts: [PortfolioCostPart]? = nil) {
            self.txId = txId
            self.acquired = acquired
            self.time = time
            self.kind = kind
            self.amount = amount
            self.cost = cost
            if let cost {
                if let parts {
                    for p in parts { PortfolioCostParts.add(&self.parts, index: &partIndex, p.time, p.usdt) }
                } else {
                    PortfolioCostParts.add(&self.parts, index: &partIndex, acquired, cost)
                }
            }
        }

        /// Gebühr bzw. übernommener Einstand `usdt` zum Zeitpunkt `at` (nur mit Kaufpreis).
        func addCost(_ at: Int64, _ usdt: Decimal) {
            guard let c = cost else { return }
            cost = c + usdt
            PortfolioCostParts.add(&parts, index: &partIndex, at, usdt)
        }

        /// Übernimmt Einstand samt Herkunft `from` (nur mit Kaufpreis).
        func addParts(_ from: [PortfolioCostPart]) {
            for p in from { addCost(p.time, p.usdt) }
        }

        /// Einstand der Teilmenge `qty` (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; nil ohne Kaufpreis.
        func takeCost(_ qty: Decimal) -> (cost: Decimal, parts: [PortfolioCostPart])? {
            guard let c = cost else { return nil }
            let removed = qty == amount ? c : PortfolioDecimal.divide(c * qty, amount)
            let taken = PortfolioCostParts.take(&parts, total: c, target: removed)
            // `take` ändert Teile nur an ihrem Platz oder leert alle
            if parts.isEmpty { partIndex.removeAll() }
            cost = c - removed
            return (removed, taken)
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
        /// Offene Posten in FIFO-Reihenfolge.
        var lots: ArraySlice<Lot> { items[head...] }
        /// Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis.
        var pendingLost: [PortfolioLostFeeCost] = []
        /// Herkunft von `pendingLost` (Summe gleich).
        var pendingParts: [PortfolioCostPart] = []

        init(key: String) { self.key = key }

        /// Ältester Posten; nil = leer.
        var first: Lot? { head < items.count ? items[head] : nil }
        var isEmpty: Bool { head >= items.count }

        /// Ältesten Posten entfernen (nur den Kopf weiterrücken; ab der Hälfte verbrauchter Plätze aufräumen).
        func removeFirst() {
            guard head < items.count else { return }
            head += 1
            if head == items.count {
                items.removeAll(keepingCapacity: true)
                head = 0
            } else if head >= 64 && head * 2 >= items.count {
                items.removeFirst(head)
                head = 0
            }
        }

        /// `lot` an seinen Platz (meist hinten): hinter allen Posten, vor denen er nicht liegt (`Lot.isBefore`).
        func insert(_ lot: Lot) {
            var i = items.count
            while i > head && lot.isBefore(items[i - 1]) { i -= 1 }
            items.insert(lot, at: i)
            held += lot.amount
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
        let parts: [PortfolioCostPart]
    }

    /// Summe der Einstände (ohne Kaufpreis = 0).
    static func costSum(_ slices: [Slice]) -> Decimal { slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) } }
}
