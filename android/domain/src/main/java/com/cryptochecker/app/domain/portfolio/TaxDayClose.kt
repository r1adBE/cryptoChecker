// Eine Tageskurs-Regel für den ganzen Steuer-Export (gespiegelt in `TaxDayClose.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Welcher Tagesschluss gilt, wenn der Steuer-Export einen Marktkurs schätzen muss: IMMER der Schluss des UTC-Tags der
 * Transaktion — für Gebühr-Zeilen, Verschenkt/bezahlt und Tausch ohne Wert, Belohnungen ohne Wert, Netzwerkgebühren als
 * Veräusserung und den FR-Gesamtwert.
 *
 * Warum diese Regel: Karte und Wertverlauf ([PortfolioPeriodChange.marketPriceAt]), Einkünfte ([TaxExport.incomeRows]) und
 * Gebühr-Zeilen rechneten schon so, und die Quellen liefern Tageskerzen in UTC (Schluss = 24:00 UTC). Nähmen
 * Netzwerkgebühren und der FR-Gesamtwert den Vortag, bewertete dieselbe Unterlage eine Kraken-Gebühr und die
 * Netzwerkgebühr derselben Stunde verschieden. Behörden verlangen einen nachvollziehbaren, stetig angewandten
 * Tageskurs; die Regel steht darum im Kopf jeder Datei.
 */
object TaxDayClose {

    /** UTC-Tag der Transaktion zur Zeit [time] (ms) — dessen Schluss gilt. */
    fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * (Coin, Tag) für Netzwerkgebühren in Krypto als Veräusserung bis [end] (ms): Gebühr im Coin bzw. im dritten Coin ohne
     * erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung (ohne Adress-Einträge, ohne Stablecoins).
     */
    fun networkFeeNeeds(trades: List<PortfolioTrade>, end: Long): Set<Pair<String, LocalDate>> {
        val out = LinkedHashSet<Pair<String, LocalDate>>()
        for (t in trades) {
            val moves = t.type == PortfolioTxType.TRANSFER || t.type.isWithdrawalLike
            if (!moves || t.time > end || AddressBalance.isAddressTrade(t.id)) continue
            val coin = PortfolioCalculator.normalizeCoin(t.coin)
            if (t.coinFee.signum() > 0 && t.validPrice == null && PortfolioStables.needsQuote(coin)) out += coin to day(t.time)
            val third = t.thirdFeeCoin
            if (third != null && t.thirdFeePrice == null && PortfolioStables.needsQuote(third)) out += third to day(t.time)
        }
        return out
    }

    /** Abfrage (Coin, Zeit) → Schluss des UTC-Tags aus [closes] (Coin, Tag); fehlt = null. */
    fun lookup(closes: Map<Pair<String, LocalDate>, BigDecimal>): (String, Long) -> BigDecimal? =
        { coin, time -> closes[coin to day(time)] }
}
