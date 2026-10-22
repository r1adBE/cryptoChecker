package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

// Durchschnittskosten-Methode ([CostMethod.AVERAGE]) der Buchhaltung je Coin — Regeln in [PortfolioCalculator].

/**
 * Durchschnittskosten-Methode (siehe oben), mit einer Veräusserung je Verkauf (Teil mit/ohne Kaufpreis, dazu
 * der ungedeckte Rest bei «mehr verkauft als erfasst»). Der Einstand wird zusätzlich nach Herkunft geführt
 * ([PortfolioCostPart]: Zeitpunkt und Betrag je Zugang/Gebühr); ein Abgang nimmt alle Teile anteilig mit —
 * so rechnet der Steuer-Export den Ø-Einstand zum Devisenkurs JE Zugang um.
 */
internal fun PortfolioCalculator.average(symbol: String, events: List<PortfolioLedgerEvent>): PortfolioBookResult {
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC

    var priced = zero       // Menge mit Kaufpreis
    var unpriced = zero     // Menge ohne Kaufpreis
    var cost = zero         // Einstand des Teils mit Kaufpreis (inkl. Gebühren in USDT)
    val parts = ArrayList<PortfolioCostPart>() // Herkunft von [cost]
    var realized = zero
    var unknownSold = zero  // verkaufte Menge mit unbekanntem Gewinn/Verlust
    var soldTotal = zero
    var withdrawn = zero
    var oversold = false
    var feeValueMissing = false
    var realizedFeeMissing = false
    val disposals = ArrayList<PortfolioDisposal>()
    val withdrawals = ArrayList<PortfolioWithdrawalCost>()
    val lostFeeCosts = ArrayList<PortfolioLostFeeCost>()
    val unassignedFees = ArrayList<PortfolioUnassignedFee>()

    /** Ergebnis von [take]: abgegebene Menge, Anteil mit Kaufpreis davor, abgegebener Einstand samt Herkunft. */
    class Taken(
        val removed: BigDecimal,
        val allPriced: Boolean,
        val share: BigDecimal,
        val costRemoved: BigDecimal,
        val removedPriced: BigDecimal,
        val partsRemoved: List<PortfolioCostPart>,
    )

    fun addCost(time: Long, usdt: BigDecimal) {
        cost += usdt
        if (usdt.signum() != 0) parts += PortfolioCostPart(time, usdt)
    }

    /** Nimmt [removed] von [before] (Einstand davor) anteilig aus allen Herkunftsteilen; Summe exakt [removed]. */
    fun takeParts(removed: BigDecimal, before: BigDecimal): List<PortfolioCostPart> {
        if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
        if (removed >= before) {
            val all = parts.toList()
            parts.clear()
            return all
        }
        val out = ArrayList<PortfolioCostPart>(parts.size)
        var left = removed
        for (i in parts.indices) {
            val p = parts[i]
            val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(before, mc)
            left -= r
            out += PortfolioCostPart(p.time, r)
            parts[i] = PortfolioCostPart(p.time, p.usdt - r)
        }
        return out
    }

    /** Gibt [requested] ab (gekappt auf den Bestand > 0), beide Teile anteilig samt Einstand. */
    fun take(requested: BigDecimal): Taken {
        val holdings = priced + unpriced
        val removed = requested.min(holdings)
        val allPriced = unpriced.signum() == 0
        val share = if (allPriced) BigDecimal.ONE else priced.divide(holdings, mc)
        val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(share, mc)).min(priced)
        val costRemoved = when {
            priced.signum() == 0 -> zero
            removedPriced >= priced -> cost
            else -> cost.multiply(removedPriced).divide(priced, mc)
        }
        val partsRemoved = takeParts(costRemoved, cost)
        // Gesamtbestand bleibt exakt; der Teil ohne Preis ist der Rest
        val rest = holdings - removed
        priced -= removedPriced
        cost -= costRemoved
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) { priced = zero; cost = zero; parts.clear() }
        unpriced = (rest - priced).max(zero)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
        return Taken(removed, allPriced, share, costRemoved, removedPriced, partsRemoved)
    }

    /**
     * Veräusserungen eines Abgangs: Teil mit Kaufpreis (Einstand zum Ø), Teil ohne Kaufpreis und der ungedeckte
     * Rest [uncovered] (mehr abgegeben als erfasst). [proceeds] = Erlös des GANZEN Abgangs; [coveredProceeds] =
     * Anteil des gedeckten Teils.
     */
    fun record(
        t: PortfolioTrade,
        taken: Taken?,
        hadPriced: Boolean,
        proceeds: BigDecimal?,
        coveredProceeds: BigDecimal?,
        uncovered: BigDecimal,
        feeLeg: Boolean,
        feeMissing: Boolean,
        swap: Boolean = false,
    ) {
        // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
        val gift = !feeLeg && t.fromGift
        val payment = !feeLeg && t.type == PortfolioTxType.PAYMENT && !t.fromGift
        val estimated = !feeLeg && t.valueEstimated
        fun add(
            amount: BigDecimal,
            costPart: BigDecimal?,
            proceedsPart: BigDecimal?,
            gain: BigDecimal?,
            costParts: List<PortfolioCostPart> = emptyList(),
            isUncovered: Boolean = false,
        ) {
            if (amount.signum() <= 0) return
            disposals += PortfolioDisposal(
                coin = symbol, sellTxId = t.id, sellTime = t.time, lotTxId = null, acquiredTime = null, lotKind = null,
                amount = amount, costUsdt = costPart, proceedsUsdt = proceedsPart, gainUsdt = gain, holdingDays = null,
                feeLeg = feeLeg, feeValueMissing = feeMissing, method = CostMethod.AVERAGE,
                costParts = costParts, uncovered = isUncovered, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
            )
        }
        if (taken != null) {
            if (!hadPriced) {
                add(taken.removed, null, coveredProceeds, null)
            } else {
                val pricedProceeds = coveredProceeds?.let { if (taken.allPriced) it else it.multiply(taken.share, mc) }
                add(
                    taken.removedPriced, taken.costRemoved, pricedProceeds, pricedProceeds?.let { it - taken.costRemoved },
                    taken.partsRemoved,
                )
                if (!taken.allPriced) add(taken.removed - taken.removedPriced, null, coveredProceeds?.let { c -> pricedProceeds?.let { c - it } }, null)
            }
        }
        // Ungedeckter Rest: Erlös bekannt, Kaufpreis fehlt
        add(uncovered, null, proceeds?.let { p -> coveredProceeds?.let { p - it } ?: p }, null, isUncovered = true)
    }

    /** Gebühr als Aufwand ([PortfolioFeeExpenses]): realisiert −Aufwand, eine Veräusserung mit Erlös 0. */
    fun expense(e: PortfolioLedgerEvent, kind: PortfolioFeeExpense, x: PortfolioFeeExpenses.Expense?) {
        if (x == null) return
        realized -= x.usdt
        if (x.feeValueMissing) feeValueMissing = true
        disposals += PortfolioFeeExpenses.disposal(symbol, e, kind, x, CostMethod.AVERAGE)
    }

    /** Erlösanteil des gedeckten Teils [removed] von [requested] (nach Menge). */
    fun coveredShare(proceeds: BigDecimal?, removed: BigDecimal, requested: BigDecimal): BigDecimal? = proceeds?.let {
        when {
            removed.compareTo(requested) == 0 -> it
            removed.signum() <= 0 -> null
            else -> it.multiply(removed).divide(requested, mc)
        }
    }

    for (e in events) {
        val t = e.trade
        val amount = t.amount
        if (amount.signum() <= 0) continue
        if (e.swapIn) {
            // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); ohne Wert ohne Kaufpreis
            val received = t.swapReceived
            if (received.signum() <= 0) {
                // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                expense(e, PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                continue
            }
            val value = t.swapValue
            if (value != null) {
                addCost(t.time, value)
                priced += received
            } else {
                unpriced += received
            }
            continue
        }
        if (e.feeLeg) {
            // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
            val fee = t.thirdFeeAmount
            val holdings = priced + unpriced
            if (fee > holdings) oversold = true
            val feePrice = t.thirdFeePrice
            val proceeds = feePrice?.let { fee * it }
            if (holdings.signum() <= 0) {
                record(t, null, false, proceeds, null, fee, feeLeg = true, feeMissing = false)
                continue
            }
            val hadPriced = priced.signum() > 0
            val taken = take(fee)
            val covered = coveredShare(proceeds, taken.removed, fee)
            if (covered != null && hadPriced) {
                realized += (if (taken.allPriced) covered else covered.multiply(taken.share, mc)) - taken.costRemoved
                if (!taken.allPriced) unknownSold += taken.removed.multiply(BigDecimal.ONE - taken.share, mc)
            } else {
                unknownSold += taken.removed
            }
            record(t, taken, hadPriced, proceeds, covered, fee - taken.removed, feeLeg = true, feeMissing = false)
            soldTotal += taken.removed
            continue
        }
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt ([PortfolioLedger.salePrice])
        val price = PortfolioLedger.salePrice(t)
        val feeValue = t.thirdFeeValue
        when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                val received = t.received
                if (received.signum() <= 0) {
                    // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
                    expense(e, PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                if (price != null) {
                    // Gebühren in USDT und im dritten Coin gehören zum Einstand (Herkunft: Erwerb)
                    addCost(t.acquisitionTime, amount * price + t.quoteFee + (feeValue ?: zero))
                    priced += received
                    if (feeValue == null) feeValueMissing = true
                } else {
                    // Ohne Kaufpreis ist der Einstand unbekannt — eine USDT-Gebühr gehört dazu und
                    // zählt deshalb nirgends als bekannter Betrag (Teil «ohne Preis»)
                    unpriced += received
                }
            }
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                val swap = t.type == PortfolioTxType.SWAP
                val holdings = priced + unpriced
                // Abgegeben: Menge + Gebühr im Coin, gekappt auf den Bestand (mehr als vorhanden: markiert)
                val requested = amount + t.coinFee
                if (requested > holdings) oversold = true
                // Erlös des ganzen Abgangs (Tausch: sein Wert); der gedeckte Teil bekommt seinen Anteil nach Menge
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { amount * it }
                val proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }
                val feeMissing = gross != null && feeValue == null
                if (feeMissing) feeValueMissing = true
                if (holdings.signum() <= 0) {
                    record(t, null, false, proceeds, null, requested, feeLeg = false, feeMissing = feeMissing, swap = swap)
                    continue
                }
                val hadPriced = priced.signum() > 0
                val taken = take(requested)
                val sold = if (taken.removed.compareTo(requested) == 0) amount else taken.removed.multiply(amount).divide(requested, mc)
                val covered = coveredShare(proceeds, taken.removed, requested)
                // Verkauf anteilig auf beide Teile; nur der Teil mit Kaufpreis ist bekannt
                if (covered != null && hadPriced) {
                    realized += (if (taken.allPriced) covered else covered.multiply(taken.share, mc)) - taken.costRemoved
                    if (!taken.allPriced) unknownSold += sold.multiply(BigDecimal.ONE - taken.share, mc)
                    if (feeValue == null) realizedFeeMissing = true
                } else {
                    unknownSold += sold
                }
                record(t, taken, hadPriced, proceeds, covered, requested - taken.removed, feeLeg = false, feeMissing = feeMissing, swap = swap)
                soldTotal += sold
            }
            // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
            PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                // Aus dem Portfolio genommen: zum Ø-Einstand, kein realisierter Gewinn
                val holdings = priced + unpriced
                val requested = amount + t.coinFee
                if (requested > holdings) oversold = true
                // Gebühr auf den nicht erfassten Teil (auch bei leerem Bestand): vermerkt statt still verloren
                val uncoveredFee = PortfolioFeeExpenses.withdrawalUncovered(t, holdings)
                if (uncoveredFee.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, uncoveredFee)
                if (holdings.signum() <= 0) continue
                // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                expense(e, PortfolioFeeExpense.WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings))
                val taken = take(requested)
                val sold = if (taken.removed.compareTo(requested) == 0) amount else taken.removed.multiply(amount).divide(requested, mc)
                withdrawn += sold
                withdrawals += PortfolioWithdrawalCost(t, sold, taken.removed, taken.removedPriced, taken.costRemoved)
            }
            PortfolioTxType.TRANSFER -> {
                val holdings = priced + unpriced
                val coinFee = t.coinFee
                // Wie FIFO je Wallet: Menge + Netzwerkgebühr über dem Bestand → markiert; der Gebühr-Anteil der nicht
                // gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr) wird vermerkt (leerer Bestand: die ganze Gebühr)
                if (amount + coinFee > holdings) oversold = true
                val lost = PortfolioFeeExpenses.transferUncovered(t, (holdings - coinFee).max(zero))
                if (holdings.signum() <= 0) {
                    if (lost.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, lost)
                    continue
                }
                val allPriced = unpriced.signum() == 0
                // Rest der Gebühr USDT + Wert der Gebühr im dritten Coin: zum Einstand des Teils mit Kaufpreis; der
                // Anteil des Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: der ganze Rest) wird vermerkt
                val extra = t.quoteFee + (feeValue ?: zero) - lost
                val assigned = if (priced.signum() > 0) {
                    if (feeValue == null) feeValueMissing = true
                    if (allPriced) extra else extra.multiply(priced).divide(holdings, mc)
                } else zero
                if (extra.signum() > 0) addCost(t.time, assigned)
                val noted = extra - assigned + lost
                if (noted.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, noted)
                // Netzwerkgebühr im Coin: nur die Menge sinkt, der Einstand bleibt im Rest (Kosten des Haltens)
                if (coinFee.signum() > 0) {
                    val removed = coinFee.min(holdings)
                    val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(priced).divide(holdings, mc)).min(priced)
                    priced -= removedPriced
                    if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) {
                        // Kein Teil mit Kaufpreis mehr: Einstand nicht still verlieren, sondern vermerken
                        if (cost.signum() != 0) lostFeeCosts += PortfolioLostFeeCost(symbol, t.id, t.time, cost)
                        priced = zero; cost = zero; parts.clear()
                    }
                    unpriced = (holdings - removed - priced).max(zero)
                    if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
                }
            }
        }
    }
    return PortfolioBookResult(
        priced = priced, unpriced = unpriced, cost = cost, realized = realized,
        unknownSold = unknownSold, soldTotal = soldTotal, withdrawn = withdrawn, oversold = oversold,
        feeValueMissing = feeValueMissing, realizedFeeMissing = realizedFeeMissing,
        lots = emptyList(), disposals = disposals, withdrawals = withdrawals,
        lostFeeCosts = lostFeeCosts,
        unassignedFees = unassignedFees,
        openParts = parts.toList(),
    )
}
