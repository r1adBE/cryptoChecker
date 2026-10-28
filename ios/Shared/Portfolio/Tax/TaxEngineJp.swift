import Foundation

// JP: Gesamtdurchschnitt je Coin und Kalenderjahr — wie `TaxEngineJp.kt` (Android).

extension TaxEngines {

    static func jpTotal(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                                _ lost: inout [PortfolioLostFeeCost]) -> [PortfolioDisposal] {
        var carryPriced: Decimal = 0
        var carryUnpriced: Decimal = 0
        var carryCost: Decimal = 0
        var carryParts: [PortfolioCostPart] = []
        // Einstand (Übertrag bzw. Zugang des Jahres) enthält einen geschätzten Wert: jeder Abgang des Jahres zum Stückpreis
        var carryEstimated = false
        var out: [PortfolioDisposal] = []
        var byYear: [Int: [TaxStep]] = [:]
        for s in steps { byYear[day(s.time, zone).year, default: []].append(s) }
        for year in byYear.keys.sorted() {
            let ys = byYear[year] ?? []
            // Jahressumme: Anfangsbestand + Zugänge des Jahres (Gebühren von Umbuchungen gehören nicht dazu)
            var totPriced = carryPriced
            var totUnpriced = carryUnpriced
            var totCost = carryCost
            var parts = carryParts
            var estimated = carryEstimated
            for s in ys where s.kind == .acquire {
                if let c = s.cost {
                    totPriced += s.qty
                    totCost += c
                    if c != 0 { parts.append(PortfolioCostPart(time: s.acquired, usdt: c)) }
                    if s.costEstimated && c != 0 { estimated = true }
                } else {
                    totUnpriced += s.qty
                }
            }
            let totQty = totPriced + totUnpriced
            let share: Decimal = totQty > 0 ? PortfolioDecimal.divide(totPriced, totQty) : 1
            let unit: Decimal = totPriced > 0 ? PortfolioDecimal.divide(totCost, totPriced) : 0
            var held = carryPriced + carryUnpriced
            var costOut: Decimal = 0
            func remove(_ qty: Decimal) -> TaxTaken {
                let removed = max(0, min(qty, held))
                held -= removed
                let removedPriced: Decimal = share == 1 ? removed : PortfolioDecimal.multiply(removed, share)
                let c: Decimal = totPriced > 0 ? PortfolioDecimal.multiply(removedPriced, unit) : 0
                costOut += c
                return TaxTaken(removed: removed, removedPriced: removedPriced, cost: c,
                                parts: PortfolioCostPartList(scaleParts(parts, total: totCost, target: c)),
                                estimated: estimated && c != 0)
            }
            var lastFee: TaxStep? = nil
            for s in ys {
                switch s.kind {
                case .acquire:
                    held += s.qty
                case .dispose:
                    let taken = remove(s.qty)
                    var list = piecesOf(taken)
                    let rest = s.qty - taken.removed
                    if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                    out += emit(coin, s, list, proceeds: s.proceeds)
                case .remove:
                    _ = remove(s.qty)
                case .networkFee:
                    held = max(0, held - s.qty)
                    lastFee = s
                case .addFee:
                    break
                }
            }
            // Übertrag: Restmenge (Anteil mit Kaufpreis wie im Jahr), Rest-Einstand samt Herkunft
            carryPriced = share == 1 ? held : PortfolioDecimal.multiply(held, share)
            carryUnpriced = held - carryPriced
            let restRaw: Decimal = max(0, totCost - costOut)
            // Ohne Rest mit Kaufpreis: Einstand der Netzwerkgebühren nicht still verlieren, sondern vermerken
            if let fee = lastFee, carryPriced <= 0 || PortfolioDecimal.isDust(carryPriced), !PortfolioDecimal.isDust(restRaw) {
                note(&lost, coin, fee, restRaw)
            }
            let restCost: Decimal = carryPriced > 0 ? restRaw : 0
            carryParts = scaleParts(parts, total: totCost, target: restCost)
            carryCost = restCost
            carryEstimated = estimated && restCost != 0
        }
        return out
    }
}
