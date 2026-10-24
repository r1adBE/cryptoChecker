import Foundation

// Durchschnittskosten-Methode (`.AVERAGE`) der Buchhaltung je Coin — Regeln in `PortfolioCalculator`;
// wie `PortfolioCalculatorAverage.kt` (Android).

extension PortfolioCalculator {

    /// Ergebnis von `take`: abgegebene Menge, Anteil mit Kaufpreis davor, abgegebener Einstand samt Herkunft.
    fileprivate struct Taken {
        let removed: Decimal
        let allPriced: Bool
        let share: Decimal
        let costRemoved: Decimal
        let removedPriced: Decimal
        let partsRemoved: [PortfolioCostPart]
    }

    /// Bestand eines Coins während der Rechnung (Teil mit/ohne Kaufpreis, Einstand samt Herkunft).
    fileprivate struct Book {
        var priced: Decimal = 0      // Menge mit Kaufpreis
        var unpriced: Decimal = 0    // Menge ohne Kaufpreis
        var cost: Decimal = 0        // Einstand des Teils mit Kaufpreis (inkl. Gebühren in USDT)
        var parts: [PortfolioCostPart] = [] // Herkunft von `cost` (nur mit `withParts`)
        /// Herkunft führen? Ohne bleibt `parts` leer und jeder Schritt kostet gleich viel (linear statt Zugänge × Abgänge).
        let withParts: Bool

        var holdings: Decimal { priced + unpriced }

        mutating func addCost(_ time: Int64, _ usdt: Decimal) {
            cost += usdt
            if withParts && usdt != 0 { parts.append(PortfolioCostPart(time: time, usdt: usdt)) }
        }

        /// Einstand und Herkunft ganz verwerfen (Teil mit Kaufpreis ist leer).
        mutating func resetPriced() {
            priced = 0
            cost = 0
            parts = []
        }

        /// Nimmt `removed` von `before` (Einstand davor) anteilig aus allen Herkunftsteilen; Summe exakt `removed`.
        mutating func takeParts(_ removed: Decimal, before: Decimal) -> [PortfolioCostPart] {
            guard removed != 0, !parts.isEmpty else { return [] }
            if removed >= before {
                let all = parts
                parts = []
                return all
            }
            var out: [PortfolioCostPart] = []
            var left = removed
            for i in parts.indices {
                let p = parts[i]
                let r = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt * removed, before)
                left -= r
                out.append(PortfolioCostPart(time: p.time, usdt: r))
                parts[i] = PortfolioCostPart(time: p.time, usdt: p.usdt - r)
            }
            return out
        }

        /// Gibt `requested` ab (gekappt auf den Bestand > 0), beide Teile anteilig samt Einstand — wie `take` (Android).
        mutating func take(_ requested: Decimal) -> Taken {
            let total = holdings
            let removed = min(requested, total)
            let allPriced = unpriced == 0
            // Teilungen und anteilige Produkte auf 34 Stellen wie Android (`PortfolioDecimal.divide`)
            let share: Decimal = allPriced ? 1 : PortfolioDecimal.divide(priced, total)
            let removedPriced = allPriced ? removed : min(PortfolioDecimal.amount(PortfolioDecimal.multiply(removed, share)), priced)
            let costRemoved: Decimal
            if priced == 0 {
                costRemoved = 0
            } else if removedPriced >= priced {
                costRemoved = cost
            } else {
                costRemoved = PortfolioDecimal.divide(cost * removedPriced, priced)
            }
            let partsRemoved = takeParts(costRemoved, before: cost)
            // Gesamtbestand bleibt exakt; der Teil ohne Preis ist der Rest
            let rest = total - removed
            priced -= removedPriced
            cost -= costRemoved
            if PortfolioDecimal.isDust(priced) || priced < 0 { resetPriced() }
            unpriced = max(0, rest - priced)
            if PortfolioDecimal.isDust(unpriced) { unpriced = 0 }
            return Taken(removed: removed, allPriced: allPriced, share: share, costRemoved: costRemoved,
                         removedPriced: removedPriced, partsRemoved: partsRemoved)
        }
    }

    /// Durchschnittskosten-Methode (siehe oben), mit einer Veräusserung je Verkauf (Teil mit/ohne Kaufpreis, dazu der
    /// ungedeckte Rest bei «mehr verkauft als erfasst»). Der Einstand wird zusätzlich nach Herkunft geführt
    /// (`PortfolioCostPart`); ein Abgang nimmt alle Teile anteilig mit — so rechnet der Steuer-Export den Ø-Einstand zum
    /// Devisenkurs JE Zugang um. Wie Android.
    /// `withParts`: Herkunft führen (Steuer-Export, Einstand der Karte in einer Anzeigewährung). Ohne sind
    /// `PortfolioDisposal.costParts` und `openParts` leer, alle Zahlen (Bestand, Einstand, realisiert …) gleich.
    static func average(_ symbol: String, events: [PortfolioLedgerEvent], withParts: Bool = true) -> PortfolioBookResult {
        var book = Book(withParts: withParts)
        var realized: Decimal = 0
        var unknownSold: Decimal = 0 // verkaufte Menge mit unbekanntem Gewinn/Verlust
        var soldTotal: Decimal = 0
        var withdrawn: Decimal = 0
        var oversold = false
        var feeValueMissing = false
        var realizedFeeMissing = false
        var disposals: [PortfolioDisposal] = []
        var withdrawals: [PortfolioWithdrawalCost] = []
        var lostFeeCosts: [PortfolioLostFeeCost] = []
        var unassignedFees: [PortfolioUnassignedFee] = []

        /// Veräusserungen eines Abgangs: Teil mit Kaufpreis (Einstand zum Ø), Teil ohne Kaufpreis und der ungedeckte
        /// Rest `uncovered`. `proceeds` = Erlös des GANZEN Abgangs; `coveredProceeds` = Anteil des gedeckten Teils.
        func record(_ t: PortfolioTx, _ taken: Taken?, hadPriced: Bool, proceeds: Decimal?, coveredProceeds: Decimal?,
                    uncovered: Decimal, feeLeg: Bool, feeMissing: Bool, swap: Bool = false) {
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            let gift = !feeLeg && t.fromGift
            let payment = !feeLeg && t.type == .PAYMENT && !t.fromGift
            let estimated = !feeLeg && t.valueEstimated
            func add(_ amount: Decimal, cost: Decimal?, proceeds part: Decimal?, gain: Decimal?,
                     costParts: [PortfolioCostPart] = [], isUncovered: Bool = false) {
                guard amount > 0 else { return }
                disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                    amount: amount, costUsdt: cost, proceedsUsdt: part, gainUsdt: gain, holdingDays: nil,
                    feeLeg: feeLeg, feeValueMissing: feeMissing, method: .AVERAGE,
                    costParts: costParts, uncovered: isUncovered, swap: swap, gift: gift, payment: payment, valueEstimated: estimated))
            }
            if let taken {
                if !hadPriced {
                    add(taken.removed, cost: nil, proceeds: coveredProceeds, gain: nil)
                } else {
                    let pricedProceeds = coveredProceeds.map { taken.allPriced ? $0 : PortfolioDecimal.multiply($0, taken.share) }
                    add(taken.removedPriced, cost: taken.costRemoved, proceeds: pricedProceeds,
                        gain: pricedProceeds.map { $0 - taken.costRemoved }, costParts: taken.partsRemoved)
                    if !taken.allPriced {
                        var rest: Decimal?
                        if let coveredProceeds, let pricedProceeds { rest = coveredProceeds - pricedProceeds }
                        add(taken.removed - taken.removedPriced, cost: nil, proceeds: rest, gain: nil)
                    }
                }
            }
            // Ungedeckter Rest: Erlös bekannt, Kaufpreis fehlt
            var restProceeds: Decimal?
            if let proceeds { restProceeds = coveredProceeds.map { proceeds - $0 } ?? proceeds }
            add(uncovered, cost: nil, proceeds: restProceeds, gain: nil, isUncovered: true)
        }

        /// Gebühr als Aufwand (`PortfolioFeeExpenses`): realisiert −Aufwand, eine Veräusserung mit Erlös 0.
        func expense(_ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense, _ x: PortfolioFeeExpenses.Expense?) {
            guard let x else { return }
            realized -= x.usdt
            if x.feeValueMissing { feeValueMissing = true }
            disposals.append(PortfolioFeeExpenses.disposal(symbol, e, kind, x, method: .AVERAGE))
        }

        /// Erlösanteil des gedeckten Teils `removed` von `requested` (nach Menge).
        func coveredShare(_ proceeds: Decimal?, removed: Decimal, requested: Decimal) -> Decimal? {
            guard let proceeds else { return nil }
            if removed == requested { return proceeds }
            if removed <= 0 { return nil }
            return PortfolioDecimal.divide(proceeds * removed, requested)
        }

        for e in events {
            let t = e.trade
            let amount = t.amountExact
            guard amount > 0 else { continue }
            if e.swapIn {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); ohne Wert ohne Kaufpreis
                let received = t.swapReceived
                if received <= 0 {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                if let value = t.swapValue {
                    book.addCost(t.time, value)
                    book.priced += received
                } else {
                    book.unpriced += received
                }
                continue
            }
            if e.feeLeg {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                let fee = t.thirdFeeAmount
                if fee > book.holdings { oversold = true }
                let proceeds = t.thirdFeePrice.map { fee * $0 }
                if book.holdings <= 0 {
                    record(t, nil, hadPriced: false, proceeds: proceeds, coveredProceeds: nil, uncovered: fee,
                           feeLeg: true, feeMissing: false)
                    continue
                }
                let hadPriced = book.priced > 0
                let taken = book.take(fee)
                let covered = coveredShare(proceeds, removed: taken.removed, requested: fee)
                if let covered, hadPriced {
                    realized += (taken.allPriced ? covered : PortfolioDecimal.multiply(covered, taken.share)) - taken.costRemoved
                    if !taken.allPriced { unknownSold += PortfolioDecimal.multiply(taken.removed, 1 - taken.share) }
                } else {
                    unknownSold += taken.removed
                }
                record(t, taken, hadPriced: hadPriced, proceeds: proceeds, coveredProceeds: covered,
                       uncovered: fee - taken.removed, feeLeg: true, feeMissing: false)
                soldTotal += taken.removed
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
                    expense(e, .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                if let price {
                    // Gebühren in USDT und im dritten Coin gehören zum Einstand (Herkunft: Erwerb)
                    book.addCost(t.acquisitionTime, amount * price + t.quoteFeeExact + (feeValue ?? 0))
                    book.priced += received
                    if feeValue == nil { feeValueMissing = true }
                } else {
                    book.unpriced += received
                }
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                let swap = t.type == .SWAP
                let holdings = book.holdings
                // Abgegeben: Menge + Gebühr im Coin, gekappt auf den Bestand (mehr als vorhanden: markiert)
                let requested = amount + t.coinFeeExact
                if requested > holdings { oversold = true }
                // Erlös des ganzen Abgangs (Tausch: sein Wert); der gedeckte Teil bekommt seinen Anteil nach Menge
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { amount * $0 }
                let proceeds = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                let feeMissing = gross != nil && feeValue == nil
                if feeMissing { feeValueMissing = true }
                if holdings <= 0 {
                    record(t, nil, hadPriced: false, proceeds: proceeds, coveredProceeds: nil, uncovered: requested,
                           feeLeg: false, feeMissing: feeMissing, swap: swap)
                    continue
                }
                let hadPriced = book.priced > 0
                let taken = book.take(requested)
                let sold = taken.removed == requested ? amount : PortfolioDecimal.divide(taken.removed * amount, requested)
                let covered = coveredShare(proceeds, removed: taken.removed, requested: requested)
                // Verkauf anteilig auf beide Teile; nur der Teil mit Kaufpreis ist bekannt
                if let covered, hadPriced {
                    realized += (taken.allPriced ? covered : PortfolioDecimal.multiply(covered, taken.share)) - taken.costRemoved
                    if !taken.allPriced { unknownSold += PortfolioDecimal.multiply(sold, 1 - taken.share) }
                    if feeValue == nil { realizedFeeMissing = true }
                } else {
                    unknownSold += sold
                }
                record(t, taken, hadPriced: hadPriced, proceeds: proceeds, coveredProceeds: covered,
                       uncovered: requested - taken.removed, feeLeg: false, feeMissing: feeMissing, swap: swap)
                soldTotal += sold
            case .WITHDRAWAL, .GIFT:
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                // Aus dem Portfolio genommen: zum Ø-Einstand, kein realisierter Gewinn
                let holdings = book.holdings
                let requested = amount + t.coinFeeExact
                if requested > holdings { oversold = true }
                // Gebühr auf den nicht erfassten Teil (auch bei leerem Bestand): vermerkt statt still verloren
                let uncoveredFee = PortfolioFeeExpenses.withdrawalUncovered(t, holdings: holdings)
                if uncoveredFee > 0 {
                    unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: uncoveredFee))
                }
                if holdings <= 0 { continue }
                // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                expense(e, .WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings: holdings))
                let taken = book.take(requested)
                let sold = taken.removed == requested ? amount : PortfolioDecimal.divide(taken.removed * amount, requested)
                withdrawn += sold
                withdrawals.append(PortfolioWithdrawalCost(trade: t, sold: sold, removed: taken.removed,
                                                           pricedRemoved: taken.removedPriced, cost: taken.costRemoved))
            case .TRANSFER:
                let holdings = book.holdings
                let coinFee = t.coinFeeExact
                // Wie FIFO je Wallet: Menge + Netzwerkgebühr über dem Bestand → markiert; der Gebühr-Anteil der nicht
                // gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr) wird vermerkt (leerer Bestand: die ganze Gebühr)
                if amount + coinFee > holdings { oversold = true }
                let lost = PortfolioFeeExpenses.transferUncovered(t, covered: max(0, holdings - coinFee))
                if holdings <= 0 {
                    if lost > 0 { unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: lost)) }
                    continue
                }
                let allPriced = book.unpriced == 0
                // Rest der Gebühr USDT + Wert der Gebühr im dritten Coin: zum Einstand des Teils mit Kaufpreis; der Anteil
                // des Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: der ganze Rest) wird vermerkt
                let extra = t.quoteFeeExact + (feeValue ?? 0) - lost
                var assigned: Decimal = 0
                if book.priced > 0 {
                    if feeValue == nil { feeValueMissing = true }
                    assigned = allPriced ? extra : PortfolioDecimal.divide(extra * book.priced, holdings)
                }
                if extra > 0 { book.addCost(t.time, assigned) }
                let noted = extra - assigned + lost
                if noted > 0 { unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: noted)) }
                // Netzwerkgebühr im Coin: nur die Menge sinkt, der Einstand bleibt im Rest (Kosten des Haltens)
                if coinFee > 0 {
                    let removed = min(coinFee, holdings)
                    let removedPriced = allPriced ? removed
                        : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed * book.priced, holdings)), book.priced)
                    book.priced -= removedPriced
                    if PortfolioDecimal.isDust(book.priced) || book.priced < 0 {
                        // Kein Teil mit Kaufpreis mehr: Einstand nicht still verlieren, sondern vermerken
                        if book.cost != 0 {
                            lostFeeCosts.append(PortfolioLostFeeCost(coin: symbol, txId: t.id, time: t.time, costUsdt: book.cost))
                        }
                        book.resetPriced()
                    }
                    book.unpriced = max(0, holdings - removed - book.priced)
                    if PortfolioDecimal.isDust(book.unpriced) { book.unpriced = 0 }
                }
            }
        }

        return PortfolioBookResult(
            priced: book.priced, unpriced: book.unpriced, cost: book.cost, realized: realized,
            unknownSold: unknownSold, soldTotal: soldTotal, withdrawn: withdrawn, oversold: oversold,
            feeValueMissing: feeValueMissing, realizedFeeMissing: realizedFeeMissing,
            lots: [], disposals: disposals, withdrawals: withdrawals, lostFeeCosts: lostFeeCosts,
            unassignedFees: unassignedFees, openParts: book.parts)
    }
}
