import Foundation

/// FIFO-Buchhaltung eines Coins — Regeln exakt wie `PortfolioFifo.kt` (dort ausführlich beschrieben):
///  - Kauf, Belohnung, Einzahlung: neuer Posten (ohne Preis mit unbekanntem Einstand).
///  - Reihenfolge der Posten: nach Erwerb (`PortfolioTx.acquisitionTime`, bei einer Einzahlung mit ursprünglichem
///    Kaufdatum dieses), dann Zeit der Transaktion, dann Id; Abgänge verbrauchen immer die ältesten ERWORBENEN Posten.
///    Haltedauer ab dem Erwerb.
///  - Verkauf: verbraucht Posten, älteste zuerst — zuerst die verkaufte Menge, danach die Gebühr im Coin aus den
///    nächsten Posten; Erlös nur nach VERKAUFTER Menge verteilt (Gebühr-Coins: Erlös 0, ihr Einstand = Kosten der
///    Veräusserung in ihrem Posten), Gewinn je Teil = Erlösanteil − Einstand; Teile ohne Kaufpreis (oder Verkauf ohne Preis) unbekannt. Mehr
///    verkauft als erfasst: Bestand gekappt und markiert; der ungedeckte Rest wird eine Veräusserung ohne Kaufpreis
///    (`uncovered`), damit im Steuer-Export kein Verkauf fehlt.
///  - Auszahlung: verbraucht Posten, älteste zuerst, ohne Gewinn und ohne Veräusserung (Einstand in `withdrawals`);
///    Gebühr USDT/dritter Coin: Aufwand (`PortfolioFeeExpenses`).
///  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs): kein
///    Posten, Aufwand realisiert (`PortfolioFeeExpenses`).
///  - Umbuchung: Netzwerkgebühr im Coin verbraucht Menge (älteste zuerst), ihr bekannter Einstand geht auf den
///    ältesten verbleibenden Posten mit Kaufpreis über (Kosten des Haltens/Verschiebens, keine Veräusserung — wird mit
///    dem nächsten Verkauf realisiert); ohne solchen Posten wartet er auf den nächsten später hinzukommenden Posten mit
///    Kaufpreis, erst ohne einen solchen bis zum Ende `PortfolioLostFeeCost`; Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf alle Posten
///    (Anteil ohne Kaufpreis gehört zu deren unbekanntem Einstand und wird als `PortfolioUnassignedFee` vermerkt; wie
///    je Wallet ebenso der Anteil der Menge über Bestand − Netzwerkgebühr, `PortfolioFeeExpenses.transferUncovered`, markiert oversold).
///  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt (Erwerb, Umbuchungsgebühr zum
///    Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen); ein Abgang nimmt sie anteilig mit
///    (`PortfolioDisposal.costParts`), der Steuer-Export rechnet jeden Teil zum Kurs SEINES Datums um. Anfangsposten des
///    US-Übergangs tragen nur ihr Erwerbsdatum (Steuerwährung USD, Kurs 1).
///  - Gebühr im dritten Coin: Veräusserung des Gebühr-Coins (FIFO) zum Gebühr-Preis (`feeLeg`).
///  - Tausch: im abgegebenen Coin wie ein Verkauf (Erlös = Wert − Gebühr USDT − Wert der Gebühr im dritten Coin), im
///    erhaltenen Coin ein Posten `.SWAP` (Einstand = Wert, Erwerb = Zeit des Tauschs, Menge − Gebühr im erhaltenen Coin).
///  - Je Wallet (`perWallet`): je Wallet eine Warteschlange; Umbuchung A → B verschiebt (nach der Netzwerkgebühr) die
///    ältesten Posten von A mit Kaufdatum nach B; fehlt Bestand in einer Wallet, wird nicht aus anderen genommen
///    (ungedeckter Rest bzw. kommt nicht an, `PortfolioWalletShortfall`).
///  - Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
enum PortfolioFifo {
    /// UTC-Kalendertage zwischen `from` und `to`.
    static func holdingDays(from: Int64, to: Int64) -> Int {
        PortfolioHistory.epochDay(utcMillis: to) - PortfolioHistory.epochDay(utcMillis: from)
    }

    /// `seed`: Anfangsposten je Wallet (`PortfolioLot.wallet` = Anzeigename) vor den Ereignissen — US-Übergang
    /// (`TaxEngines.usTransition`); sonst leer. Wie Android.
    static func run(_ symbol: String, events: [PortfolioLedgerEvent], perWallet: Bool = false,
                    seed: [PortfolioLot] = []) -> PortfolioBookResult {
        let method: CostMethod = perWallet ? .FIFO_WALLET : .FIFO
        var queues: [Queue] = []
        var queueIndex: [String: Int] = [:]
        var names: [String: String] = [:]
        var r = PortfolioBookResult()

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
            if lot.cost != nil, !q.pendingLost.isEmpty {
                lot.addParts(q.pendingParts)
                q.pendingLost.removeAll()
                q.pendingParts.removeAll()
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
                let taken = lot.takeCost(qty)
                lot.amount -= qty
                q.held -= qty
                if lot.amount <= 0 { q.removeFirst() }
                left -= qty
                out.append(Slice(txId: lot.txId, acquired: lot.acquired, time: lot.time, kind: lot.kind, amount: qty,
                                 cost: taken?.cost, parts: taken?.parts ?? []))
            }
            if q.isEmpty { q.held = 0 }
            return out
        }

        /// Gebühr USDT + Wert der Gebühr im dritten Coin (`extra`) einer Umbuchung zum Zeitpunkt `at` nach Menge auf `lots`
        /// (Summe `h`); der letzte Posten bekommt den Rest, wenn alle einen Kaufpreis haben. Rückgabe = Anteil der Posten
        /// OHNE Kaufpreis (`unassign`); 0 = alles zugeordnet.
        func spread(_ lots: ArraySlice<Lot>, _ extra: Decimal, _ h: Decimal, at: Int64) -> Decimal {
            guard extra > 0, h > 0 else { return 0 }
            let allPriced = lots.allSatisfy { $0.cost != nil }
            let last = lots.count - 1
            var left = extra
            var assigned: Decimal = 0
            for (i, lot) in lots.enumerated() {
                let part = allPriced && i == last ? left : PortfolioDecimal.divide(extra * lot.amount, h)
                left -= part
                if lot.cost != nil {
                    lot.addCost(at, part)
                    assigned += part
                }
            }
            return extra - assigned
        }

        /// Einstand der Netzwerkgebühr ohne bezahlten Posten in `q` (`carried`): nicht still verlieren — wartet auf den
        /// nächsten Posten mit Kaufpreis (`insert`), sonst am Ende vermerkt.
        func lose(_ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            let cost = costSum(carried)
            if cost == 0 { return }
            q.pendingLost.append(PortfolioLostFeeCost(coin: symbol, txId: t.id, time: t.time, costUsdt: cost))
            for s in carried { for p in s.parts { PortfolioCostParts.add(&q.pendingParts, p.time, p.usdt) } }
        }

        /// Netzwerkgebühr-Einstand `carried` auf `target` (Posten mit Kaufpreis), sonst wartend in `q` (`lose`).
        func carry(_ target: Lot?, _ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            if costSum(carried) == 0 { return }
            if let target {
                for s in carried { target.addParts(s.parts) }
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
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            let gift = !feeLeg && t.fromGift
            let payment = !feeLeg && t.type == .PAYMENT && !t.fromGift
            let estimated = !feeLeg && t.valueEstimated
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
                    else { share = PortfolioDecimal.divide(proceeds * w, weightTotal) }
                    proceedsLeft = left - (share ?? 0)
                }
                var gain: Decimal?
                if let share, let cost = s.cost { gain = share - cost }
                if let gain {
                    r.realized += gain
                } else if soldParts != nil {
                    r.unknownSold += w
                } else {
                    r.unknownSold += s.amount == removed ? sold : PortfolioDecimal.divide(sold * s.amount, removed)
                }
                r.disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time,
                    lotTxId: s.txId, acquiredTime: s.acquired, lotKind: s.kind,
                    amount: s.amount, costUsdt: s.cost, proceedsUsdt: share, gainUsdt: gain,
                    holdingDays: holdingDays(from: s.acquired, to: t.time),
                    feeLeg: feeLeg, feeValueMissing: feeMissing, method: method, costParts: s.parts, wallet: wallet, swap: swap,
                    gift: gift, payment: payment, valueEstimated: estimated))
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

        // Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet
        for l in seed where l.amount > 0 {
            insert(queue(l.wallet), Lot(txId: l.txId, acquired: l.acquiredTime, time: l.acquiredTime, kind: l.kind,
                                        amount: l.amount, cost: l.costUsdt))
        }

        for e in events {
            let t = e.trade
            let amount = t.amountExact
            guard amount > 0 else { continue }
            if e.feeLeg {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                let q = queue(PortfolioWallets.of(e))
                let fee = t.thirdFeeAmount
                check(q, fee, t, feeLeg: true)
                let slices = consume(q, fee)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                dispose(t, q, slices, uncovered: fee - removed, proceeds: t.thirdFeePrice.map { fee * $0 }, sold: removed,
                        feeLeg: true, feeMissing: false)
                r.soldTotal += removed
                continue
            }
            if e.swapIn {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
                // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
                let received = t.swapReceived
                if received <= 0 {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                insert(queue(t.exchange), Lot(txId: t.id, acquired: t.time, time: t.time, kind: .SWAP, amount: received,
                                              cost: t.swapValue))
                continue
            }
            // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt (`PortfolioLedger.salePrice`)
            let price = PortfolioLedger.salePrice(t)
            let feeValue = t.thirdFeeValue
            switch t.type {
            case .BUY, .REWARD, .DEPOSIT:
                let received = t.receivedExact
                if received <= 0 {
                    // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
                    expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                let cost = price.map { amount * $0 + t.quoteFeeExact + (feeValue ?? 0) }
                if price != nil && feeValue == nil { r.feeValueMissing = true }
                insert(queue(t.exchange), Lot(txId: t.id, acquired: t.acquisitionTime, time: t.time, kind: t.type,
                                              amount: received, cost: cost))
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                let swap = t.type == .SWAP
                let q = queue(t.exchange)
                let requested = amount + t.coinFeeExact
                check(q, requested, t, feeLeg: false)
                let slices = consume(q, requested)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                // Zuerst die verkaufte Menge, danach die Gebühr-Coins (FIFO in einem Zug): verkaufter Teil je Posten
                var before: Decimal = 0
                var soldParts: [Decimal] = []
                for s in slices {
                    soldParts.append(min(max(amount - before, 0), s.amount))
                    before += s.amount
                }
                let sold = soldParts.reduce(Decimal(0), +)
                // Erlös des ganzen Abgangs (Tausch: sein Wert) nur nach verkaufter Menge verteilt (Gebühr-Coins: Erlös 0)
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { amount * $0 }
                let proceeds = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                let feeMissing = gross != nil && feeValue == nil
                if feeMissing && removed > 0 { r.realizedFeeMissing = true }
                if feeMissing { r.feeValueMissing = true }
                dispose(t, q, slices, uncovered: requested - removed, proceeds: proceeds, sold: sold, feeLeg: false,
                        feeMissing: feeMissing, swap: swap, soldParts: soldParts, uncoveredSold: amount - sold)
                r.soldTotal += sold
            case .WITHDRAWAL, .GIFT:
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
                let q = queue(t.exchange)
                let requested = amount + t.coinFeeExact
                check(q, requested, t, feeLeg: false)
                // Gebühr auf den nicht erfassten Teil (auch aus leerer Warteschlange): vermerkt statt still verloren
                unassign(t, PortfolioFeeExpenses.withdrawalUncovered(t, holdings: q.held))
                if q.held <= 0 { continue }
                // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                expense(e, q, .WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings: q.held))
                let slices = consume(q, requested)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                let sold = removed == requested ? amount : PortfolioDecimal.divide(removed * amount, requested)
                r.withdrawn += sold
                r.withdrawals.append(PortfolioWithdrawalCost(
                    trade: t, sold: sold, removed: removed,
                    pricedRemoved: slices.filter { $0.cost != nil }.reduce(Decimal(0)) { $0 + $1.amount },
                    cost: slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) }))
            case .TRANSFER:
                let src = queue(t.transferFrom)
                let dst = queue(t.transferTo)
                let coinFee = t.coinFeeExact
                if src !== dst {
                    // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
                    check(src, amount + coinFee, t, feeLeg: false)
                    // Leere Quelle → die ganze Gebühr vermerkt statt still verworfen
                    if src.held <= 0 {
                        unassign(t, PortfolioFeeExpenses.transferUncovered(t, covered: 0))
                        continue
                    }
                    // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
                    let carried = consume(src, coinFee)
                    let moved = consume(src, amount).map {
                        Lot(txId: $0.txId, acquired: $0.acquired, time: $0.time, kind: $0.kind, amount: $0.amount, cost: $0.cost,
                            parts: $0.parts)
                    }
                    // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil ohne
                    // Kaufpreis und der Anteil der nicht angekommenen Menge werden vermerkt
                    if feeValue == nil && moved.contains(where: { $0.cost != nil }) { r.feeValueMissing = true }
                    let movedAmount = moved.reduce(Decimal(0)) { $0 + $1.amount }
                    let lost = PortfolioFeeExpenses.transferUncovered(t, covered: movedAmount)
                    unassign(t, lost + spread(moved[...], t.quoteFeeExact + (feeValue ?? 0) - lost, movedAmount, at: t.time))
                    // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
                    // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
                    let target = moved.first(where: { $0.cost != nil }) ?? src.lots.first(where: { $0.cost != nil })
                        ?? dst.lots.first(where: { $0.cost != nil })
                    carry(target, moved.isEmpty ? src : dst, t, carried)
                    for lot in moved { insert(dst, lot) }
                    continue
                }
                let q = src
                let h = q.held
                // Wie je Wallet: Menge + Netzwerkgebühr über dem Bestand → markiert; der Gebühr-Anteil der nicht
                // gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr) wird vermerkt (leerer Bestand: die ganze Gebühr)
                check(q, amount + coinFee, t, feeLeg: false)
                let lost = PortfolioFeeExpenses.transferUncovered(t, covered: max(0, h - coinFee))
                if h <= 0 {
                    unassign(t, lost)
                    continue
                }
                // Rest der Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der Anteil
                // der Posten ohne Kaufpreis wird vermerkt
                if feeValue == nil && q.lots.contains(where: { $0.cost != nil }) { r.feeValueMissing = true }
                unassign(t, lost + spread(q.lots, t.quoteFeeExact + (feeValue ?? 0) - lost, h, at: t.time))
                // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten Posten mit
                // Kaufpreis über (Kosten des Haltens, keine Veräusserung)
                if coinFee > 0 {
                    let carried = consume(q, coinFee)
                    carry(q.lots.first(where: { $0.cost != nil }), q, t, carried)
                }
            }
        }

        // Gebühr-Einstand, für den bis zum Ende kein Posten mit Kaufpreis kam: vermerkt (zeitlich, stabil wie Android)
        let lost: [PortfolioLostFeeCost] = r.lostFeeCosts + queues.flatMap(\.pendingLost)
        r.lostFeeCosts = lost.enumerated().sorted { a, b in
            if a.element.time != b.element.time { return a.element.time < b.element.time }
            if a.element.txId != b.element.txId { return a.element.txId < b.element.txId }
            return a.offset < b.offset
        }.map(\.element)
        var outLots: [PortfolioLot] = []
        for q in queues {
            for l in q.lots {
                if let cost = l.cost {
                    r.priced += l.amount
                    r.cost += cost
                    r.openParts += l.parts
                } else {
                    r.unpriced += l.amount
                }
                outLots.append(PortfolioLot(coin: symbol, txId: l.txId, acquiredTime: l.acquired, kind: l.kind,
                                            amount: l.amount, costUsdt: l.cost, wallet: walletOf(q)))
            }
        }
        r.lots = outLots
        return r
    }
}
