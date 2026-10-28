import Foundation

// Schritte und gemeinsame Hilfen der Länder-Rechnungen (Teil von `TaxEngines`): Gebühr-Einstand vermerken, Schritte
// eines Coins, Stücke und Zeilen ausgeben, Herkunftsteile skalieren — wie `TaxEngines.kt` (Android).

extension TaxEngines {
    /// Vermerkt Einstand `cost` einer Netzwerkgebühr (`s`) ohne Rest mit Kaufpreis (0 = nichts).
    static func note(_ lost: inout [PortfolioLostFeeCost], _ coin: String, _ s: TaxStep, _ cost: Decimal) {
        if cost != 0 { lost.append(PortfolioLostFeeCost(coin: coin, txId: s.id, time: s.time, costUsdt: cost)) }
    }

    /// Vermerkt den Anteil `usdt` einer Umbuchungsgebühr (`s`) auf Bestand ohne Kaufpreis (`TaxPool.addFee`; 0 = nichts).
    static func noteUnassigned(_ unassigned: inout [PortfolioUnassignedFee], _ coin: String, _ s: TaxStep,
                                       _ usdt: Decimal) {
        if usdt > 0 { unassigned.append(PortfolioUnassignedFee(coin: coin, txId: s.id, time: s.time, usdt: usdt)) }
    }

    static func day(_ millis: Int64, _ zone: TimeZone) -> LocalDay {
        LocalDay(date: Date(timeIntervalSince1970: Double(millis) / 1000), timeZone: zone)
    }

    /// Schritte eines Coins aus seinen Ereignissen — wie `steps` (Android).
    static func steps(_ events: [PortfolioLedgerEvent]) -> [TaxStep] {
        var out: [TaxStep] = []
        for e in events {
            let t = e.trade
            guard t.amountExact > 0 else { continue }
            if e.feeLeg {
                let fee = t.thirdFeeAmount
                let p: Decimal? = t.thirdFeePrice.map { PortfolioDecimal.multiply(fee, $0) }
                out.append(TaxStep(.dispose, t, qty: fee, proceeds: p, gross: p, feeLeg: true, estimated: t.feePriceEstimated))
                continue
            }
            if e.swapIn {
                let r = t.swapReceived
                if r > 0 {
                    out.append(TaxStep(.acquire, t, qty: r, cost: t.swapValue, swap: true, costEstimated: t.costEstimated(swapIn: true)))
                }
                continue
            }
            let price = t.validPriceExact
            let feeValue = t.thirdFeeValue
            switch t.type {
            case .BUY, .REWARD, .DEPOSIT:
                let r = t.receivedExact
                guard r > 0 else { continue }
                let cost: Decimal? = price.map { PortfolioDecimal.multiply(t.amountExact, $0) + t.quoteFeeExact + (feeValue ?? 0) }
                out.append(TaxStep(.acquire, t, qty: r, cost: cost, acquired: t.acquisitionTime, costEstimated: t.costEstimated()))
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen; Verschenkt nur nach
                // `applyRules` mit `giftIsDisposal`, sonst wie eine Auszahlung)
                let swap = t.type == .SWAP
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { PortfolioDecimal.multiply(t.amountExact, $0) }
                let proceeds: Decimal? = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                out.append(TaxStep(.dispose, t, qty: t.amountExact + t.coinFeeExact, proceeds: proceeds, gross: gross,
                                   swap: swap, feeMissing: gross != nil && feeValue == nil,
                                   gift: t.fromGift, payment: t.type == .PAYMENT && !t.fromGift,
                                   // Geschätzter Gebühr-Preis im dritten Coin: der Erlös enthält ihn
                                   // (`PortfolioLedger.valueThirdFeesAtDayClose`)
                                   estimated: t.valueEstimated || (t.feePriceEstimated && gross != nil)))
            case .WITHDRAWAL, .GIFT:
                // Verschenkt (beim Schenkenden keine Veräusserung): wie eine Auszahlung; an Ehe-/Lebenspartner (Merker nur
                // nach `applyRules` mit UK/CA-Regeln): Veräusserung ohne Gewinn/Verlust zum zugeordneten Einstand
                if t.isGiftToSpouse {
                    out.append(TaxStep(.dispose, t, qty: t.amountExact + t.coinFeeExact, gift: true, spouseGift: true))
                } else {
                    out.append(TaxStep(.remove, t, qty: t.amountExact + t.coinFeeExact))
                }
            case .TRANSFER:
                let extra: Decimal = t.quoteFeeExact + (feeValue ?? 0)
                if extra > 0 { out.append(TaxStep(.addFee, t, qty: 0, cost: extra, costEstimated: t.feePriceEstimated)) }
                if t.coinFeeExact > 0 { out.append(TaxStep(.networkFee, t, qty: t.coinFeeExact)) }
            }
        }
        return out
    }

    /// Ein Teil einer Veräusserung vor der Erlös-Verteilung.
    struct Piece {
        let amount: Decimal
        let cost: Decimal?
        var parts: PortfolioCostPartList = []
        var acquiredTime: Int64? = nil
        var lotTxId: Int64? = nil
        var lotKind: PortfolioTxType? = nil
        var match: TaxMatch? = nil
        var matchedTime: Int64? = nil
        var uncovered = false
        var oldHolding: Bool? = nil
        /// `cost` enthält einen geschätzten Wert (`TaxTaken.estimated`): Vermerk «Wert geschätzt» an der Zeile (`emit`).
        var estimated = false
    }

    /// Teile eines Abgangs aus `taken`: Teil mit Kaufpreis (Einstand, Herkunft) und Teil ohne.
    static func piecesOf(_ taken: TaxTaken, match: TaxMatch? = nil, matchedTime: Int64? = nil,
                                 oldHolding: Bool? = nil) -> [Piece] {
        var out: [Piece] = []
        if taken.removedPriced > 0 {
            out.append(Piece(amount: taken.removedPriced, cost: taken.cost, parts: taken.parts, match: match,
                             matchedTime: matchedTime, oldHolding: oldHolding, estimated: taken.estimated))
        }
        if taken.removedUnpriced > 0 {
            out.append(Piece(amount: taken.removedUnpriced, cost: nil, match: match, matchedTime: matchedTime,
                             oldHolding: oldHolding))
        }
        return out
    }

    /// Veräusserungen aus `pieces`: Erlös nach Menge verteilt (letzter Teil bekommt den Rest, Summe exakt). Schenkung an
    /// Ehe-/Lebenspartner (`TaxStep.spouseGift`): Erlös je Teil = sein Einstand (ohne Kaufpreis unbekannt). `gross` (UK):
    /// Erlös vor Gebühren, gleich verteilt (`PortfolioDisposal.proceedsBeforeFeesUsdt`); nil = keiner. «Wert geschätzt»:
    /// Erlös geschätzt (`TaxStep.estimated`) oder Einstand des Teils mit geschätztem Wert (`Piece.estimated`). Wie Android.
    static func emit(_ coin: String, _ s: TaxStep, _ pieces: [Piece], proceeds: Decimal?,
                     gross: Decimal? = nil) -> [PortfolioDisposal] {
        let total = pieces.reduce(Decimal(0)) { $0 + $1.amount }
        guard total > 0 else { return [] }
        var left = proceeds
        var grossLeft = gross
        var out: [PortfolioDisposal] = []
        for (i, p) in pieces.enumerated() {
            var share: Decimal? = nil
            if s.spouseGift {
                share = p.cost
            } else if let proceeds, let l = left {
                share = i == pieces.count - 1 ? l : PortfolioDecimal.divide(proceeds, p.amount, by: total)
                left = l - (share ?? 0)
            }
            var grossShare: Decimal? = nil
            if !s.spouseGift, let gross, let g = grossLeft {
                grossShare = i == pieces.count - 1 ? g : PortfolioDecimal.divide(gross, p.amount, by: total)
                grossLeft = g - (grossShare ?? 0)
            }
            var gain: Decimal? = nil
            if let share, let c = p.cost { gain = share - c }
            let holding: Int? = p.acquiredTime.map { PortfolioFifo.holdingDays(from: $0, to: s.time) }
            var d = PortfolioDisposal(
                coin: coin, sellTxId: s.id, sellTime: s.time, lotTxId: p.lotTxId, acquiredTime: p.acquiredTime,
                lotKind: p.lotKind, amount: p.amount, costUsdt: p.cost, proceedsUsdt: share, gainUsdt: gain,
                holdingDays: holding, feeLeg: s.feeLeg, feeValueMissing: s.feeMissing,
                method: p.acquiredTime != nil ? .FIFO : .AVERAGE,
                costParts: p.parts, uncovered: p.uncovered, swap: s.swap, gift: s.gift, payment: s.payment,
                valueEstimated: s.estimated || (p.estimated && p.cost != nil))
            d.match = p.match
            d.matchedTime = p.matchedTime
            d.oldHolding = p.oldHolding
            d.spouseGift = s.spouseGift
            d.proceedsBeforeFeesUsdt = grossShare
            out.append(d)
        }
        return out
    }

    // Gemeinsame Hilfen der Länder-Rechnungen (TaxEngineUk/Ca/Jp/At/Fr.swift)

    /// Herkunftsteile `parts` (Summe `total`) auf den Anteil `target` skaliert; Summe exakt `target`.
    static func scaleParts(_ parts: [PortfolioCostPart], total: Decimal, target: Decimal) -> [PortfolioCostPart] {
        guard target != 0, total != 0, !parts.isEmpty else { return [] }
        if target == total { return parts }
        var left = target
        var out: [PortfolioCostPart] = []
        for (i, p) in parts.enumerated() {
            let v: Decimal = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt, target, by: total)
            left -= v
            out.append(PortfolioCostPart(time: p.time, usdt: v))
        }
        return out
    }

    /// Ereignisse aller Coins in Reihenfolge der Transaktionen: Haupt-Coin, Gebühr-Coin, erhaltener Coin.
    static func mergedEvents(_ real: [PortfolioTx]) -> [(coin: String, event: PortfolioLedgerEvent)] {
        // Gleiche Reihenfolge wie das Buch (`PortfolioLedger.precedes`): Zeit, bei gleicher Zeit Zugänge vor Abgängen, dann Id
        let sorted = real.enumerated().sorted { a, b in
            PortfolioLedger.precedes(PortfolioLedgerEvent(trade: a.element, feeLeg: false),
                                     PortfolioLedgerEvent(trade: b.element, feeLeg: false)) ?? (a.offset < b.offset)
        }.map(\.element)
        var out: [(coin: String, event: PortfolioLedgerEvent)] = []
        for t in sorted {
            out.append((PortfolioCalculator.normalizeCoin(t.coin), PortfolioLedgerEvent(trade: t, feeLeg: false)))
            if let fc = t.thirdFeeCoin { out.append((fc, PortfolioLedgerEvent(trade: t, feeLeg: true))) }
            if let tc = t.swapToCoin { out.append((tc, PortfolioLedgerEvent(trade: t, feeLeg: false, swapIn: true))) }
        }
        return out
    }

    /// Mitternacht des Tags `day` in `zone` (Millisekunden).
    static func startMillis(_ d: LocalDay, _ zone: TimeZone) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let date = cal.date(from: DateComponents(year: d.year, month: d.month, day: d.day)) ?? Date(timeIntervalSince1970: 0)
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
}
