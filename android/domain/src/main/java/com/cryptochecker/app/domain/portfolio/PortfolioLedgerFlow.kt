// Geldfluss eines Ledger-Schritts (Ein- und Ausgang in USDT) — Teil von PortfolioLedger.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Zufluss von [e] in USDT bei Bestand [holdingsBefore] (siehe oben); null = unbekannt.
 * [marketPrice] = Marktkurs des Coins zum Zeitpunkt von [e] (nur nötig, wenn [needsMarketPrice]);
 * [feeMarketPrice] = Marktkurs des Gebühr-Coins dann (nur nötig, wenn [needsFeeMarketPrice]). Gerechnet wird exakt
 * ([BigDecimal]: Menge × Kurs, Gebühren, Anteil) und erst das Ergebnis als Double zurückgegeben — Börsenkurse kommen
 * als Double und gehen über ihre kürzeste Darstellung ein. [method] = Einstandsmethode des Buchs
 * ([PortfolioHoldingsBook.method]): bestimmt den gedeckten Anteil eines Verkaufs/Tauschs über den Bestand hinaus
 * ([coveredShare]).
 */
fun PortfolioLedger.flow(
    e: PortfolioLedgerEvent,
    holdingsBefore: BigDecimal,
    marketPrice: Double? = null,
    feeMarketPrice: Double? = null,
    method: CostMethod = CostMethod.DEFAULT,
): Double? = flowExact(e, holdingsBefore, marketPrice, feeMarketPrice, method)?.toDouble()

/** [flow] exakt; null = unbekannt. */
fun PortfolioLedger.flowExact(
    e: PortfolioLedgerEvent,
    holdingsBefore: BigDecimal,
    marketPrice: Double? = null,
    feeMarketPrice: Double? = null,
    method: CostMethod = CostMethod.DEFAULT,
): BigDecimal? {
    val t = e.trade
    if (t.amount.signum() <= 0) return BigDecimal.ZERO
    val h = holdingsBefore.max(BigDecimal.ZERO)
    if (e.feeLeg) {
        // Ohne Gebühr-Preis zum Marktkurs des Gebühr-Coins (neutral wie eine Auszahlung ohne Preis)
        val feePrice = t.thirdFeePrice ?: exactPrice(marketPrice) ?: return null
        val removed = t.thirdFeeAmount.min(h)
        return if (removed.signum() > 0) removed.multiply(feePrice, PortfolioDecimal.MC).negate() else BigDecimal.ZERO
    }
    val market = exactPrice(marketPrice)
    if (e.swapIn) {
        // Zugang aus Tausch: zum Wert des Tauschs, sonst erhaltene Menge (vor Gebühr) × Marktkurs; ohne gültigen Zugang unbekannt
        if (t.swapToCoin == null) return null
        t.swapValue?.let { return it }
        return market?.let { m -> t.toAmount?.multiply(m, PortfolioDecimal.MC) }
    }
    // Umbuchung ohne Bestand (in ihrer Quelle): wie im Rechner ([PortfolioFifo], Ø) ohne Wirkung — auch ihre Gebühren
    if (t.type == PortfolioTxType.TRANSFER && h.signum() <= 0) return BigDecimal.ZERO
    // Gebühr im dritten Coin ohne Preis: zum Marktkurs des Gebühr-Coins
    val feeValue = t.thirdFeeValue
        ?: exactPrice(feeMarketPrice)?.let { t.thirdFeeAmount.multiply(it, PortfolioDecimal.MC) }
        ?: return null
    // Gebühr-Zeile zum Marktkurs (Abfluss wie ein Verkauf, dessen Erlös das Portfolio verlässt)
    val price = if (isFeeDisposal(t)) market else t.validPrice ?: market?.takeIf { needsMarketPrice(e) }
    return when (t.type) {
        PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
            if (price == null) return null
            t.amount.multiply(price, PortfolioDecimal.MC) + t.quoteFee + feeValue
        }
        // Damit bezahlt: Wert verlässt das Portfolio wie ein Verkauf (ohne Marktwert zum Marktkurs)
        PortfolioTxType.SELL, PortfolioTxType.PAYMENT -> {
            if (price == null) return null
            // Nichts mehr da: der Verkauf zählt nicht, auch nicht seine Gebühr — wie «realisiert»
            val share = coveredShare(t, h, method) ?: return BigDecimal.ZERO
            partOf(t.amount.multiply(price, PortfolioDecimal.MC) - t.quoteFee - feeValue, share).negate()
        }
        // Verschenkt: wie eine Auszahlung (Wert verlässt das Portfolio zum Marktwert)
        PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
            val share = withdrawnShare(t, h) ?: return BigDecimal.ZERO
            if (price == null) return null
            // Wert und Gebühren zum gedeckten Anteil wie beim Verkauf (ungedeckter Rest: nichts abgegeben)
            partOf(t.amount.multiply(price, PortfolioDecimal.MC) - t.quoteFee - feeValue, share).negate()
        }
        PortfolioTxType.TRANSFER -> t.quoteFee + feeValue
        PortfolioTxType.SWAP -> {
            // Abgegebener Coin wie ein Verkauf: zum Wert des Tauschs, sonst zum Marktkurs; gedeckter Anteil wie dort
            val share = coveredShare(t, h, method) ?: return BigDecimal.ZERO
            // Zugang kaputt (kein gültiger erhaltener Coin): unbekannt statt still verloren
            if (t.swapToCoin == null) return null
            val gross = t.swapValue ?: market?.let { t.amount.multiply(it, PortfolioDecimal.MC) } ?: return null
            partOf(gross - t.quoteFee - feeValue, share).negate()
        }
    }
}
