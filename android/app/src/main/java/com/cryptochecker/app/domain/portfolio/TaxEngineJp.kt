package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId

// JP: Gesamtdurchschnitt je Coin und Kalenderjahr — Regeln in [TaxEngines].

internal fun TaxEngines.jpTotal(coin: String, steps: List<TaxStep>, zone: ZoneId, lost: MutableList<PortfolioLostFeeCost>): List<PortfolioDisposal> {
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC
    var carryPriced = zero
    var carryUnpriced = zero
    var carryCost = zero
    var carryParts: List<PortfolioCostPart> = emptyList()
    val out = ArrayList<PortfolioDisposal>()
    for ((_, ys) in steps.groupBy { day(it.time, zone).year }.toSortedMap()) {
        // Jahressumme: Anfangsbestand + Zugänge des Jahres (Gebühren von Umbuchungen gehören nicht zum Erwerbspreis)
        var totPriced = carryPriced
        var totUnpriced = carryUnpriced
        var totCost = carryCost
        val parts = ArrayList(carryParts)
        for (s in ys) if (s.kind == TaxStepKind.ACQUIRE) {
            if (s.cost != null) {
                totPriced += s.qty
                totCost += s.cost
                if (s.cost.signum() != 0) parts += PortfolioCostPart(s.acquired, s.cost)
            } else totUnpriced += s.qty
        }
        val totQty = totPriced + totUnpriced
        val share = if (totQty.signum() > 0) totPriced.divide(totQty, mc) else BigDecimal.ONE
        val unit = if (totPriced.signum() > 0) totCost.divide(totPriced, mc) else zero
        var held = carryPriced + carryUnpriced
        var costOut = zero
        fun remove(qty: BigDecimal): TaxTaken {
            val removed = qty.min(held).max(zero)
            held -= removed
            val removedPriced = if (share.compareTo(BigDecimal.ONE) == 0) removed else removed.multiply(share, mc)
            val c = if (totPriced.signum() > 0) removedPriced.multiply(unit, mc) else zero
            costOut += c
            return TaxTaken(removed, removedPriced, c, scaleParts(parts, totCost, c))
        }
        var lastFee: TaxStep? = null
        for (s in ys) {
            when (s.kind) {
                TaxStepKind.ACQUIRE -> held += s.qty
                TaxStepKind.DISPOSE -> {
                    val taken = remove(s.qty)
                    val list = ArrayList(piecesOf(taken))
                    val rest = s.qty - taken.removed
                    if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                    out += emit(coin, s, list, s.proceeds)
                }
                TaxStepKind.REMOVE -> remove(s.qty)
                TaxStepKind.NETWORK_FEE -> {
                    held = (held - s.qty).max(zero)
                    lastFee = s
                }
                TaxStepKind.ADD_FEE -> Unit
            }
        }
        // Übertrag: Restmenge (Anteil mit Kaufpreis wie im Jahr), Rest-Einstand samt Herkunft
        carryPriced = if (share.compareTo(BigDecimal.ONE) == 0) held else held.multiply(share, mc)
        carryUnpriced = held - carryPriced
        val restRaw = (totCost - costOut).max(zero)
        // Ohne Rest mit Kaufpreis: Einstand der Netzwerkgebühren nicht still verlieren, sondern vermerken
        val fee = lastFee
        if (fee != null && (carryPriced.signum() <= 0 || PortfolioDecimal.isDust(carryPriced)) && !PortfolioDecimal.isDust(restRaw)) {
            lost.note(coin, fee, restRaw)
        }
        val restCost = if (carryPriced.signum() > 0) restRaw else zero
        carryParts = scaleParts(parts, totCost, restCost)
        carryCost = restCost
    }
    return out
}
