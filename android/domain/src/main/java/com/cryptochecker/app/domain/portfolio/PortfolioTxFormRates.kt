// Umrechnung der Eingabe im Buchungsblatt (Anzeigewährung ↔ USDT) und Bestand je Wallet — Teil von PortfolioTxForm.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Faktor USDT → Anzeigewährung für die Eingabe (wie CSV-Import, Steuer-Export
 * `TaxExport.rateOn` und Live-Kurse): Devisenkurs USD → Währung [usdToFiat] × USDT/USD [usdtUsd] (heute der
 * Live-Kurs, sonst der Tagesschluss DES Tags). Ohne gültigen USDT/USD-Kurs im Einbruch-Bereich 0.05…3 ([PortfolioStables.usdtUsd])
 * × 1 (Näherung). [fiatToUsdt]/[usdtToFiat] rechnen mit diesem Faktor. null ohne Devisenkurs. Wie iOS.
 */
fun PortfolioTxForm.entryRate(usdToFiat: BigDecimal?, usdtUsd: BigDecimal?): BigDecimal? {
    if (usdToFiat == null || usdToFiat.signum() <= 0) return null
    val usdt = plausibleUsdt(usdtUsd) ?: return usdToFiat
    return usdToFiat.multiply(usdt, PortfolioDecimal.MC)
}

/**
 * Rechnet [entryRate] mit USDT = 1 (Devisenkurs da, USDT/USD fehlt oder ist unplausibel)? Dann nennt das Blatt
 * «USDT = 1 angenommen» wie Summen und Steuer-Export — die Näherung soll nie still sein. Wie iOS.
 */
fun PortfolioTxForm.entryUsdtAssumed(usdToFiat: BigDecimal?, usdtUsd: BigDecimal?): Boolean =
    usdToFiat != null && usdToFiat.signum() > 0 && plausibleUsdt(usdtUsd) == null

internal fun PortfolioTxForm.plausibleUsdt(usdtUsd: BigDecimal?): BigDecimal? =
    usdtUsd?.takeIf { it.signum() > 0 && !PortfolioStables.usdtUsd(it.toDouble()).approximate }

/**
 * Eingabe in der Anzeigewährung (CHF/EUR/…) → USDT: Betrag ÷ Faktor USDT → Währung ([usdToFiat] > 0, aus
 * [entryRate]: Devisenkurs × USDT/USD), 34 Stellen ([PortfolioDecimal.MC]). null ohne Betrag oder Kurs.
 */
fun PortfolioTxForm.fiatToUsdt(fiat: BigDecimal?, usdToFiat: BigDecimal?): BigDecimal? {
    if (fiat == null || usdToFiat == null || usdToFiat.signum() <= 0) return null
    return fiat.divide(usdToFiat, PortfolioDecimal.MC).stripTrailingZeros()
}

/** USDT → Anzeigewährung (Umschalten des Felds): Betrag × Kurs; null ohne Betrag oder Kurs. */
fun PortfolioTxForm.usdtToFiat(usdt: BigDecimal?, usdToFiat: BigDecimal?): BigDecimal? {
    if (usdt == null || usdToFiat == null || usdToFiat.signum() <= 0) return null
    return usdt.multiply(usdToFiat).round(PortfolioDecimal.MC).stripTrailingZeros()
}

/**
 * Bestand von [wallet] aus den offenen Posten ([PortfolioCalculator.openLots], [CostMethod.FIFO_WALLET]): Summe der
 * Posten derselben Wallet ([PortfolioWallets.key]; leer = «ohne Angabe»).
 */
fun PortfolioTxForm.walletHoldings(lots: List<PortfolioOpenLot>, wallet: String?): BigDecimal {
    val key = PortfolioWallets.key(wallet)
    return lots.filter { PortfolioWallets.key(it.wallet) == key }.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
}
