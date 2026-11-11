import Foundation

// Ein Lauf von `PortfolioFifo.run` — wie `PortfolioFifoBook.kt` (Android): Warteschlangen je Wallet und die laufenden
// Summen eines Coins. Hier Zustand und Bausteine (Posten einfügen/verbrauchen, Gebühren, Veräusserung); die Ereignisse
// (`apply`) und das Ergebnis in PortfolioFifoBook+Events.swift. Regeln der Buchung bei `PortfolioFifo`.

extension PortfolioFifo {
    /// Stand der FIFO-Buchhaltung eines Coins: ein Ereignis nach dem anderen (`apply`), am Ende `result`.
    final class PortfolioFifoBook {
        let symbol: String
        let perWallet: Bool
        let method: CostMethod
        var queues: [Queue] = []
        var queueIndex: [String: Int] = [:]
        var names: [String: String] = [:]
        var r = PortfolioBookResult()

        init(_ symbol: String, perWallet: Bool) {
            self.symbol = symbol
            self.perWallet = perWallet
            self.method = perWallet ? .FIFO_WALLET : .FIFO
        }

        /// Warteschlange der Wallet `raw` (universell immer dieselbe).
        func queue(_ raw: String?) -> Queue {
            let key = perWallet ? PortfolioWallets.key(raw) : PortfolioWallets.unspecified
            if perWallet && names[key] == nil {
                names[key] = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            }
            if let i = queueIndex[key] { return queues[i] }
            let q = Queue(key: key)
            queueIndex[key] = queues.count
            queues.append(q)
            return q
        }

        /// Anzeigename der Wallet von `q` (nur je Wallet, sonst nil).
        func walletOf(_ q: Queue) -> String? { perWallet ? (names[q.key] ?? PortfolioWallets.unspecified) : nil }

        /// Abgang `requested` grösser als der Bestand von `q`: markiert, je Wallet mit Fehlmenge.
        func check(_ q: Queue, _ requested: Decimal, _ t: PortfolioTx, feeLeg: Bool) {
            guard requested > q.held else { return }
            r.oversold = true
            if perWallet {
                r.shortfalls.append(PortfolioWalletShortfall(
                    coin: symbol, wallet: walletOf(q) ?? PortfolioWallets.unspecified, txId: t.id, time: t.time,
                    type: t.type, feeLeg: feeLeg, missing: requested - max(0, q.held)))
            }
        }

        /// Neuer Posten an seinem Platz (meist hinten); ein Posten mit Kaufpreis übernimmt wartenden Gebühr-Einstand.
        func insert(_ q: Queue, _ lot: Lot) {
            if lot.priced, !q.pendingLost.isEmpty {
                lot.addEntries(q.pending.items)
                if q.pendingEstimated { lot.estimated = true }
                q.pendingLost.removeAll()
                q.pending.removeAll()
                q.pendingEstimated = false
            }
            q.insert(lot)
        }

        /// Verbraucht `requested` (gekappt auf den Bestand von `q`), älteste Posten zuerst.
        func consume(_ q: Queue, _ requested: Decimal) -> [Slice] {
            var out: [Slice] = []
            var left = min(requested, q.held)
            while left > 0, let lot = q.first {
                let qty = min(left, lot.amount)
                // Einstand anteilig samt Herkunft (vor dem Kürzen der Menge)
                let taken = lot.take(qty)
                q.reduce(lot, by: qty)
                lot.amount -= qty
                if lot.amount <= 0 { q.removeFirst() }
                left -= qty
                out.append(Slice(txId: lot.txId, acquired: lot.acquired, time: lot.time, kind: lot.kind, amount: qty,
                                 cost: taken?.cost, entries: taken?.entries ?? [], estimated: lot.estimated))
            }
            q.clearHeldIfEmpty()
            return out
        }

        /// Je Wallet: Gebühr USDT + Wert der Gebühr im dritten Coin (`extra`) einer Umbuchung zum Zeitpunkt `at` nach Menge
        /// auf die verschobenen Posten `lots` (Summe `h`, je Posten ein Teil); der letzte bekommt den Rest, wenn alle einen
        /// Kaufpreis haben. Rückgabe = Anteil der Posten OHNE Kaufpreis (`unassign`); 0 = alles zugeordnet. `estimated`:
        /// `extra` enthält einen geschätzten Wert — die Posten mit Kaufpreis tragen ihn.
        func spread(_ lots: ArraySlice<Lot>, _ extra: Decimal, _ h: Decimal, at: Int64, estimated: Bool) -> Decimal {
            guard extra > 0, h > 0 else { return 0 }
            let allPriced = lots.allSatisfy { $0.cost != nil }
            let last = lots.count - 1
            var left = extra
            var assigned: Decimal = 0
            for (i, lot) in lots.enumerated() {
                let part = allPriced && i == last ? left : PortfolioDecimal.divide(extra, lot.amount, by: h)
                left -= part
                if lot.cost != nil {
                    lot.addCost(at, part)
                    assigned += part
                    if estimated { lot.estimated = true }
                }
            }
            return extra - assigned
        }

        /// Einstand der Netzwerkgebühr ohne bezahlten Posten in `q` (`carried`): nicht still verlieren — wartet auf den
        /// nächsten Posten mit Kaufpreis (`insert`), sonst am Ende vermerkt.
        func lose(_ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            let cost = PortfolioFifo.costSum(carried)
            if cost == 0 { return }
            if carried.contains(where: { $0.cost != nil && $0.estimated }) { q.pendingEstimated = true }
            q.pendingLost.append(PortfolioLostFeeCost(coin: symbol, txId: t.id, time: t.time, costUsdt: cost))
            for s in carried { for e in s.entries { q.pending.add(e) } }
        }

        /// Netzwerkgebühr-Einstand `carried` auf `target` (Posten mit Kaufpreis), sonst wartend in `q` (`lose`).
        func carry(_ target: Lot?, _ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            if PortfolioFifo.costSum(carried) == 0 { return }
            if let target {
                target.addEntries(carried.flatMap(\.entries))
                if carried.contains(where: { $0.cost != nil && $0.estimated }) { target.estimated = true }
            } else {
                lose(q, t, carried)
            }
        }

        /// Umbuchungsgebühr-Anteil `usdt` auf Bestand ohne Kaufpreis: vermerkt (`PortfolioUnassignedFee`).
        func unassign(_ t: PortfolioTx, _ usdt: Decimal) {
            if usdt > 0 { r.unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: usdt)) }
        }

        /// Gebühr als Aufwand (`PortfolioFeeExpenses`): realisiert −Aufwand, eine Veräusserung mit Erlös 0.
        func expense(_ e: PortfolioLedgerEvent, _ q: Queue, _ kind: PortfolioFeeExpense, _ x: PortfolioFeeExpenses.Expense?) {
            guard let x else { return }
            r.realized -= x.usdt
            if x.feeValueMissing { r.feeValueMissing = true }
            r.disposals.append(PortfolioFeeExpenses.disposal(symbol, e, kind, x, method: method, wallet: walletOf(q)))
        }

        /// Veräusserung: Erlös `proceeds` (nil = unbekannt) des ganzen Abgangs auf die Teile und den ungedeckten Rest
        /// `uncovered` verteilt — nach Menge, mit `soldParts` nur nach VERKAUFTER Menge je Teil (Gebühr-Coins: Erlös 0;
        /// `uncoveredSold` = verkaufte Menge im ungedeckten Rest). `sold` = verkaufte Menge ohne Gebühr im Coin, gedeckter Teil.
        func dispose(_ t: PortfolioTx, _ q: Queue, _ slices: [Slice], uncovered: Decimal, proceeds: Decimal?, sold: Decimal,
                     feeLeg: Bool, feeMissing: Bool, swap: Bool = false, soldParts: [Decimal]? = nil,
                     uncoveredSold: Decimal? = nil) {
            let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
            let total = removed + uncovered
            guard total > 0 else { return }
            let wallet = walletOf(q)
            // Schenkung, Zahlung mit Krypto, geschätzter Wert bzw. Gebühr-Preis (auch am Erlös, den sie mindert): Vermerke
            let gift = !feeLeg && t.fromGift
            let payment = !feeLeg && t.type == .PAYMENT && !t.fromGift
            let estimated = feeLeg ? t.feePriceEstimated : t.valueEstimated || (t.feePriceEstimated && proceeds != nil)
            let weights = soldParts ?? slices.map(\.amount)
            let restWeight = soldParts != nil ? (uncoveredSold ?? uncovered) : uncovered
            let weightTotal = weights.reduce(Decimal(0), +) + restWeight
            var proceedsLeft = proceeds
            // Letzter Teil MIT Gewicht bekommt den Rest: Summe der Anteile = Erlös, exakt
            let lastWeighted = restWeight > 0 ? slices.count : (weights.lastIndex(where: { $0 > 0 }) ?? -1)
            for (i, s) in slices.enumerated() {
                let w = weights[i]
                var share: Decimal?
                if let proceeds, let left = proceedsLeft {
                    if w <= 0 { share = 0 }
                    else if i == lastWeighted { share = left }
                    else { share = PortfolioDecimal.divide(proceeds, w, by: weightTotal) }
                    proceedsLeft = left - (share ?? 0)
                }
                var gain: Decimal?
                if let share, let cost = s.cost { gain = share - cost }
                if let gain {
                    r.realized += gain
                } else if soldParts != nil {
                    r.unknownSold += w
                } else {
                    r.unknownSold += s.amount == removed ? sold : PortfolioDecimal.divide(sold, s.amount, by: removed)
                }
                r.disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time,
                    lotTxId: s.txId, acquiredTime: s.acquired, lotKind: s.kind,
                    amount: s.amount, costUsdt: s.cost, proceedsUsdt: share, gainUsdt: gain,
                    holdingDays: PortfolioFifo.holdingDays(from: s.acquired, to: t.time),
                    feeLeg: feeLeg, feeValueMissing: feeMissing, method: method,
                    costParts: PortfolioCostPartList(fifo: s.entries, total: s.cost), wallet: wallet, swap: swap,
                    gift: gift, payment: payment, valueEstimated: estimated || (s.cost != nil && s.estimated)))
            }
            if uncovered > 0 {
                // Mehr abgegeben als erfasst: Erlös bekannt, Kaufpreis fehlt (nicht im realisierten Gewinn)
                r.disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time,
                    lotTxId: nil, acquiredTime: nil, lotKind: nil,
                    amount: uncovered, costUsdt: nil, proceedsUsdt: proceedsLeft, gainUsdt: nil,
                    holdingDays: nil, feeLeg: feeLeg, feeValueMissing: feeMissing, method: method,
                    uncovered: true, wallet: wallet, swap: swap, gift: gift, payment: payment, valueEstimated: estimated))
            }
        }
    }
}
