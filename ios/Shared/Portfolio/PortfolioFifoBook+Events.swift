import Foundation

// Ereignisse und Ergebnis eines Laufs von `PortfolioFifo.run` (`PortfolioFifoBook`) — wie `PortfolioFifoBook.kt` (Android).

extension PortfolioFifo.PortfolioFifoBook {
    /// Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet.
    func seed(_ lots: [PortfolioLot]) {
        for l in lots where l.amount > 0 {
            insert(queue(l.wallet), PortfolioFifo.Lot(txId: l.txId, acquired: l.acquiredTime, time: l.acquiredTime, kind: l.kind,
                                        amount: l.amount, cost: l.costUsdt, estimated: l.estimated))
        }
    }

    /// Bucht ein Ereignis des Coins.
    func apply(_ e: PortfolioLedgerEvent) {
        let t = e.trade
        guard t.amountExact > 0 else { return }
        if e.feeLeg { feeLeg(e); return }
        if e.swapIn { swapIn(e); return }
        switch t.type {
        case .BUY, .REWARD, .DEPOSIT: acquire(e)
        case .SELL, .PAYMENT, .SWAP: sell(t)
        case .WITHDRAWAL, .GIFT: withdraw(e)
        case .TRANSFER: transfer(t)
        }
    }

    private func feeLeg(_ e: PortfolioLedgerEvent) {
        let t = e.trade
        // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
        let q = queue(PortfolioWallets.of(e))
        let fee = t.thirdFeeAmount
        check(q, fee, t, feeLeg: true)
        let slices = consume(q, fee)
        let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
        dispose(t, q, slices, uncovered: fee - removed, proceeds: t.thirdFeePrice.map { PortfolioDecimal.multiply(fee, $0) }, sold: removed,
                feeLeg: true, feeMissing: false)
        r.soldTotal += removed
    }

    private func swapIn(_ e: PortfolioLedgerEvent) {
        let t = e.trade
        // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
        // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
        let received = t.swapReceived
        if received <= 0 {
            // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
            expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
            return
        }
        insert(queue(t.exchange), PortfolioFifo.Lot(txId: t.id, acquired: t.time, time: t.time, kind: .SWAP, amount: received,
                                      cost: t.swapValue, estimated: t.costEstimated(swapIn: true)))
    }

    private func acquire(_ e: PortfolioLedgerEvent) {
        let t = e.trade
        let amount = t.amountExact
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt (`PortfolioLedger.salePrice`)
        let price = PortfolioLedger.salePrice(t)
        let feeValue = t.thirdFeeValue
        let received = t.receivedExact
        if received <= 0 {
            // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
            expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
            return
        }
        let cost = price.map { PortfolioDecimal.multiply(amount, $0) + t.quoteFeeExact + (feeValue ?? 0) }
        if price != nil && feeValue == nil { r.feeValueMissing = true }
        insert(queue(t.exchange), PortfolioFifo.Lot(txId: t.id, acquired: t.acquisitionTime, time: t.time, kind: t.type,
                                      amount: received, cost: cost, estimated: t.costEstimated()))
    }

    private func sell(_ t: PortfolioTx) {
        let amount = t.amountExact
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt (`PortfolioLedger.salePrice`)
        let price = PortfolioLedger.salePrice(t)
        let feeValue = t.thirdFeeValue
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
        let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { PortfolioDecimal.multiply(amount, $0) }
        let proceeds = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
        let feeMissing = gross != nil && feeValue == nil
        if feeMissing && removed > 0 { r.realizedFeeMissing = true }
        if feeMissing { r.feeValueMissing = true }
        dispose(t, q, slices, uncovered: requested - removed, proceeds: proceeds, sold: sold, feeLeg: false,
                feeMissing: feeMissing, swap: swap, soldParts: soldParts, uncoveredSold: amount - sold)
        r.soldTotal += sold
    }

    private func withdraw(_ e: PortfolioLedgerEvent) {
        let t = e.trade
        let amount = t.amountExact
        // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
        // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
        let q = queue(t.exchange)
        let requested = amount + t.coinFeeExact
        check(q, requested, t, feeLeg: false)
        // Gebühr auf den nicht erfassten Teil (auch aus leerer Warteschlange): vermerkt statt still verloren
        unassign(t, PortfolioFeeExpenses.withdrawalUncovered(t, holdings: q.held))
        if q.held <= 0 { return }
        // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
        expense(e, q, .WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings: q.held))
        let slices = consume(q, requested)
        let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
        let sold = removed == requested ? amount : PortfolioDecimal.divide(removed, amount, by: requested)
        r.withdrawn += sold
        r.withdrawals.append(PortfolioWithdrawalCost(
            trade: t, sold: sold, removed: removed,
            pricedRemoved: slices.filter { $0.cost != nil }.reduce(Decimal(0)) { $0 + $1.amount },
            cost: slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) }))
    }

    private func transfer(_ t: PortfolioTx) {
        let amount = t.amountExact
        let feeValue = t.thirdFeeValue
        let src = queue(t.transferFrom)
        let dst = queue(t.transferTo)
        let coinFee = t.coinFeeExact
        if src !== dst {
            // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
            check(src, amount + coinFee, t, feeLeg: false)
            // Leere Quelle → die ganze Gebühr vermerkt statt still verworfen
            if src.held <= 0 {
                unassign(t, PortfolioFeeExpenses.transferUncovered(t, covered: 0))
                return
            }
            // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
            let carried = consume(src, coinFee)
            let moved = consume(src, amount).map {
                PortfolioFifo.Lot(txId: $0.txId, acquired: $0.acquired, time: $0.time, kind: $0.kind, amount: $0.amount, cost: $0.cost,
                    entries: $0.entries, estimated: $0.estimated)
            }
            // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil ohne
            // Kaufpreis und der Anteil der nicht angekommenen Menge werden vermerkt
            if feeValue == nil && moved.contains(where: { $0.cost != nil }) { r.feeValueMissing = true }
            let movedAmount = moved.reduce(Decimal(0)) { $0 + $1.amount }
            let lost = PortfolioFeeExpenses.transferUncovered(t, covered: movedAmount)
            unassign(t, lost + spread(moved[...], t.quoteFeeExact + (feeValue ?? 0) - lost, movedAmount, at: t.time,
                                        estimated: t.feePriceEstimated))
            // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
            // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
            let target = moved.first(where: { $0.cost != nil }) ?? src.lots.first(where: { $0.cost != nil })
                ?? dst.lots.first(where: { $0.cost != nil })
            carry(target, moved.isEmpty ? src : dst, t, carried)
            for lot in moved { insert(dst, lot) }
            return
        }
        let q = src
        let h = q.held
        // Wie je Wallet: Menge + Netzwerkgebühr über dem Bestand → markiert; der Gebühr-Anteil der nicht
        // gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr) wird vermerkt (leerer Bestand: die ganze Gebühr)
        check(q, amount + coinFee, t, feeLeg: false)
        let lost = PortfolioFeeExpenses.transferUncovered(t, covered: max(0, h - coinFee))
        if h <= 0 {
            unassign(t, lost)
            return
        }
        // Rest der Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der Anteil
        // der Posten ohne Kaufpreis wird vermerkt
        if feeValue == nil && q.hasPriced { r.feeValueMissing = true }
        unassign(t, lost + PortfolioFifo.spreadQueue(q, t.quoteFeeExact + (feeValue ?? 0) - lost, h, at: t.time,
                                         estimated: t.feePriceEstimated))
        // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten Posten mit
        // Kaufpreis über (Kosten des Haltens, keine Veräusserung)
        if coinFee > 0 {
            let carried = consume(q, coinFee)
            carry(q.lots.first(where: { $0.cost != nil }), q, t, carried)
        }
    }

    /// Offene Posten und Summen nach dem letzten Ereignis.
    func result() -> PortfolioBookResult {
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
                } else {
                    r.unpriced += l.amount
                }
                outLots.append(PortfolioLot(coin: symbol, txId: l.txId, acquiredTime: l.acquired, kind: l.kind,
                                            amount: l.amount, costUsdt: l.cost, wallet: walletOf(q), estimated: l.estimated))
            }
        }
        r.lots = outLots
        r.openParts = PortfolioFifo.openParts(queues, total: r.cost)
        return r
    }
}
