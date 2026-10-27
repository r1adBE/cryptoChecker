// Schritte und gemeinsame Hilfen der Länder-Rechnungen (Teil von `TaxEngines`, gespiegelt in `TaxEngines+Steps.swift`):
// Gebühr-Einstand vermerken, Schritte eines Coins, Stücke und Zeilen ausgeben, Herkunftsteile skalieren.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Vermerkt Einstand [cost] einer Netzwerkgebühr ([s]) ohne Rest mit Kaufpreis (0 = nichts). */
fun MutableList<PortfolioLostFeeCost>.note(coin: String, s: TaxStep, cost: BigDecimal) {
    if (cost.signum() != 0) add(PortfolioLostFeeCost(coin, s.id, s.time, cost))
}

/** Vermerkt den Anteil [usdt] einer Umbuchungsgebühr ([s]) auf Bestand ohne Kaufpreis ([TaxPool.addFee]; 0 = nichts). */
fun MutableList<PortfolioUnassignedFee>.noteUnassigned(coin: String, s: TaxStep, usdt: BigDecimal) {
    if (usdt.signum() > 0) add(PortfolioUnassignedFee(coin, s.id, s.time, usdt))
}

fun TaxEngines.day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** Schritte eines Coins aus seinen Ereignissen ([PortfolioLedger.events]). */
fun TaxEngines.steps(events: List<PortfolioLedgerEvent>): List<TaxStep> {
    val zero = BigDecimal.ZERO
    val out = ArrayList<TaxStep>()
    for (e in events) {
        val t = e.trade
        if (t.amount.signum() <= 0) continue
        if (e.feeLeg) {
            val fee = t.thirdFeeAmount
            val p = t.thirdFeePrice?.let { fee * it }
            out += TaxStep(TaxStepKind.DISPOSE, t, fee, proceeds = p, gross = p, feeLeg = true)
            continue
        }
        if (e.swapIn) {
            val r = t.swapReceived
            if (r.signum() > 0) out += TaxStep(TaxStepKind.ACQUIRE, t, r, cost = t.swapValue, swap = true)
            continue
        }
        val price = t.validPrice
        val feeValue = t.thirdFeeValue
        when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                val r = t.received
                if (r.signum() <= 0) continue
                val cost = price?.let { t.amount * it + t.quoteFee + (feeValue ?: zero) }
                out += TaxStep(TaxStepKind.ACQUIRE, t, r, cost = cost, acquired = t.acquisitionTime)
            }
            // Damit bezahlt: Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen; Verschenkt nur nach
            // [applyRules] mit [TaxEventRules.giftIsDisposal], sonst wie eine Auszahlung)
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                val swap = t.type == PortfolioTxType.SWAP
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { t.amount * it }
                out += TaxStep(
                    TaxStepKind.DISPOSE, t, t.amount + t.coinFee,
                    proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }, gross = gross,
                    swap = swap, feeMissing = gross != null && feeValue == null,
                    gift = t.fromGift, payment = t.type == PortfolioTxType.PAYMENT && !t.fromGift,
                    estimated = t.valueEstimated,
                )
            }
            // Verschenkt (beim Schenkenden keine Veräusserung): wie eine Auszahlung; an Ehe-/Lebenspartner (Merker nur
            // nach [applyRules] mit UK/CA-Regeln): Veräusserung ohne Gewinn/Verlust zum zugeordneten Einstand
            PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> out += if (t.isGiftToSpouse) {
                TaxStep(TaxStepKind.DISPOSE, t, t.amount + t.coinFee, gift = true, spouseGift = true)
            } else {
                TaxStep(TaxStepKind.REMOVE, t, t.amount + t.coinFee)
            }
            PortfolioTxType.TRANSFER -> {
                val extra = t.quoteFee + (feeValue ?: zero)
                if (extra.signum() > 0) out += TaxStep(TaxStepKind.ADD_FEE, t, zero, cost = extra)
                if (t.coinFee.signum() > 0) out += TaxStep(TaxStepKind.NETWORK_FEE, t, t.coinFee)
            }
        }
    }
    return out
}

/** Teile eines Abgangs aus [taken]: Teil mit Kaufpreis (Einstand, Herkunft) und Teil ohne. */
fun TaxEngines.piecesOf(taken: TaxTaken, match: TaxMatch? = null, matchedTime: Long? = null, oldHolding: Boolean? = null): List<TaxPiece> =
    listOfNotNull(
        TaxPiece(taken.removedPriced, taken.cost, taken.parts, match = match, matchedTime = matchedTime, oldHolding = oldHolding)
            .takeIf { taken.removedPriced.signum() > 0 },
        TaxPiece(taken.removedUnpriced, null, match = match, matchedTime = matchedTime, oldHolding = oldHolding)
            .takeIf { taken.removedUnpriced.signum() > 0 },
    )

/**
 * Veräusserungen aus [pieces]: Erlös [proceeds] nach Menge verteilt (letzter Teil bekommt den Rest, Summe exakt);
 * [method] wie in [PortfolioDisposal.method] (Pool/Durchschnitt = [CostMethod.AVERAGE], Posten = [CostMethod.FIFO]).
 * Schenkung an Ehe-/Lebenspartner ([TaxStep.spouseGift]): Erlös je Teil = sein Einstand (ohne Kaufpreis unbekannt).
 * [gross] (UK): Erlös vor Gebühren, gleich verteilt ([PortfolioDisposal.proceedsBeforeFeesUsdt]); null = keiner.
 */
fun TaxEngines.emit(
    coin: String,
    s: TaxStep,
    pieces: List<TaxPiece>,
    proceeds: BigDecimal?,
    gross: BigDecimal? = null,
    extra: (TaxPiece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d },
): List<PortfolioDisposal> {
    val total = pieces.fold(BigDecimal.ZERO) { a, p -> a + p.amount }
    if (total.signum() <= 0) return emptyList()
    var left = proceeds
    var grossLeft = gross
    return pieces.mapIndexed { i, p ->
        val share = if (s.spouseGift) p.cost else proceeds?.let {
            val even = it.multiply(p.amount).divide(total, PortfolioDecimal.MC)
            if (i == pieces.size - 1) left ?: even else even
        }
        if (share != null && !s.spouseGift) left = left?.minus(share)
        val grossShare = if (s.spouseGift) null else gross?.let {
            val even = it.multiply(p.amount).divide(total, PortfolioDecimal.MC)
            if (i == pieces.size - 1) grossLeft ?: even else even
        }
        if (grossShare != null) grossLeft = grossLeft?.minus(grossShare)
        val d = PortfolioDisposal(
            coin = coin, sellTxId = s.id, sellTime = s.time,
            lotTxId = p.lotTxId, acquiredTime = p.acquiredTime, lotKind = p.lotKind,
            amount = p.amount, costUsdt = p.cost, proceedsUsdt = share,
            gainUsdt = if (share != null && p.cost != null) share - p.cost else null,
            holdingDays = p.acquiredTime?.let { PortfolioFifo.holdingDays(it, s.time) },
            feeLeg = s.feeLeg, feeValueMissing = s.feeMissing,
            method = if (p.acquiredTime != null) CostMethod.FIFO else CostMethod.AVERAGE,
            costParts = p.parts, uncovered = p.uncovered, swap = s.swap, gift = s.gift, payment = s.payment, valueEstimated = s.estimated,
            match = p.match, matchedTime = p.matchedTime, oldHolding = p.oldHolding, spouseGift = s.spouseGift,
            proceedsBeforeFeesUsdt = grossShare,
        )
        extra(p, d)
    }
}

// Gemeinsame Hilfen der Länder-Rechnungen (TaxEngineUk/Ca/Jp/At/Fr.kt)

/** Kurs eines Gebühr-Coins: erfasster Preis [recorded], Stablecoin 1, sonst [priceAt]; null = unbekannt. */
fun TaxEngines.feePrice(coin: String, recorded: BigDecimal?, time: Long, priceAt: ((String, Long) -> BigDecimal?)?): BigDecimal? =
    recorded?.takeIf { it.signum() >= 0 } ?: BigDecimal.ONE.takeIf { !PortfolioStables.needsQuote(coin) }
        ?: priceAt?.invoke(coin, time)?.takeIf { it.signum() >= 0 }

/** Herkunftsteile [parts] (Summe [total]) auf den Anteil [target] skaliert; Summe exakt [target]. */
fun TaxEngines.scaleParts(parts: List<PortfolioCostPart>, total: BigDecimal, target: BigDecimal): List<PortfolioCostPart> {
    if (target.signum() == 0 || total.signum() == 0 || parts.isEmpty()) return emptyList()
    if (target.compareTo(total) == 0) return parts.toList()
    var left = target
    return parts.mapIndexed { i, p ->
        val v = if (i == parts.size - 1) left else p.usdt.multiply(target).divide(total, PortfolioDecimal.MC)
        left -= v
        PortfolioCostPart(p.time, v)
    }
}

/** Ereignisse aller Coins in Reihenfolge der Transaktionen: Haupt-Coin, Gebühr-Coin, erhaltener Coin. */
fun TaxEngines.mergedEvents(real: List<PortfolioTrade>): List<Pair<String, PortfolioLedgerEvent>> {
    val out = ArrayList<Pair<String, PortfolioLedgerEvent>>()
    // Gleiche Reihenfolge wie das Buch ([PortfolioLedger.ORDER]): Zeit, bei gleicher Zeit Zugänge vor Abgängen, dann Id
    for (t in real.sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })) {
        out += PortfolioCalculator.normalizeCoin(t.coin) to PortfolioLedgerEvent(t, false)
        t.thirdFeeCoin?.let { out += it to PortfolioLedgerEvent(t, true) }
        t.swapToCoin?.let { out += it to PortfolioLedgerEvent(t, false, swapIn = true) }
    }
    return out
}
