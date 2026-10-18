// Teile der Jahresübersicht ([TaxExport.yearSummary], gespiegelt in `TaxExport+SummaryHelpers.swift`): Summen je
// Posten ([Acc]), FR-Abgänge ohne Verkauf und der AT-Vermerk zu Belohnungen mit Anschaffungskosten 0.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId

/**
 * Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
 * Einstand (FR: Erwerbspreis-Summe nicht gesichert, [TaxGainRow.costIncomplete]), Anzahl ohne Devisenkurs.
 */
internal class Acc(rows: List<TaxGainRow>, pick: (BigDecimal) -> Boolean, fx: Boolean) {
    var usdt: BigDecimal = BigDecimal.ZERO
    var target: BigDecimal = BigDecimal.ZERO
    var unknown = 0
    var partial = 0
    var fxMissing = 0

    init {
        for (r in rows) {
            val g = r.gainUsdt
            if (g == null) { unknown++; continue }
            // Gewinn oder Verlust entscheidet die Steuerwährung (ein USDT-Verlust kann in CAD/EUR ein Gewinn sein);
            // ohne Kurs USDT
            val gT = r.gainTarget
            if (!pick(if (fx && gT != null) gT else g)) continue
            usdt += g
            // Gewinn aus unvollständigem Einstand: gezählt, aber der Posten ist «unvollständig»
            if (r.costIncomplete) partial++
            if (gT != null) target += gT else if (fx) fxMissing++
        }
    }

    /** Ein Gewinn fehlt oder beruht auf unvollständigem Einstand. */
    val incomplete: Boolean get() = unknown > 0 || partial > 0

    fun line(l: TaxLine, fx: Boolean, countUnknown: Boolean = true, note: TaxSummaryNote? = null): TaxSummaryLine =
        TaxSummaryLine(
            l, usdt, target.takeIf { fx }, incomplete = (countUnknown && unknown > 0) || partial > 0, noFx = fxMissing > 0,
            note = note,
        )
}

/**
 * FR: Abgänge ohne Verkauf im Jahr [period] — Kürzung der Erwerbspreis-Summe (BOFiP § 100) als Posten
 * [TaxLine.FR_OUTFLOW] (immer «unvollständig»); null = keine Abgänge. Teil von [yearSummary].
 */
internal fun TaxExport.frOutflowLine(
    trades: List<PortfolioTrade>,
    method: TaxMethod,
    priceAt: ((String, Long) -> BigDecimal?)?,
    period: TaxPeriod,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
    usdtUsd: Map<Long, BigDecimal>?,
    fx: Boolean,
): TaxSummaryLine? {
    val zero = BigDecimal.ZERO
    // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
    val outs = TaxEngines.outflows(trades.filter { it.amount.signum() > 0 }, method, priceAt).filter { localDate(it.time, zone) in period }
    if (outs.isEmpty()) return null
    var outUsdt = zero
    var outTarget = zero
    var outFx = false
    for (o in outs) {
        val r = o.ptaReductionUsdt ?: continue
        outUsdt += r
        // Kürzung = Anteil der Erwerbspreis-Summe: in der Steuerwährung je Herkunft zum Kurs ihres Kaufdatums (wie
        // der Einstand einer Abtretung), nicht zum Kurs des Abgangs; ohne Herkunft (Kürzung 0) zum Kurs des Abgangs
        val t = if (o.ptaReductionParts.isEmpty()) {
            rateOn(rates, localDate(o.time, zone), currency, usdtUsd)?.let { r.multiply(it, PortfolioDecimal.MC) }
        } else {
            o.ptaReductionParts.fold<PortfolioCostPart, BigDecimal?>(zero) { acc, p ->
                val rate = rateOn(rates, localDate(p.time, zone), currency, usdtUsd)
                if (acc == null || rate == null) null else acc + p.usdt.multiply(rate, PortfolioDecimal.MC)
            }
        }
        if (t != null) outTarget += t else if (fx) outFx = true
    }
    return TaxSummaryLine(TaxLine.FR_OUTFLOW, outUsdt, outTarget.takeIf { fx }, incomplete = true, noFx = outFx)
}

/**
 * AT: Belohnungen (ohne Zinsen) mit Erwerb vom 1.3.2021 bis 28.2.2022 bis zum Ende von [period] — sie stecken mit
 * Anschaffungskosten 0 im Neubestand ([TaxSummaryNote.AT_STAKING_ZERO_COST]).
 */
internal fun TaxExport.atEarlyRewards(trades: List<PortfolioTrade>, zone: ZoneId, period: TaxPeriod): Boolean =
    trades.any { t ->
        if (t.type != PortfolioTxType.REWARD || t.interestIncome || t.amount.signum() <= 0) return@any false
        val d = localDate(t.acquisitionTime, zone)
        d >= TaxTemplate.AT_NEW_FROM && d < TaxTemplate.AT_SWAP_NEUTRAL_FROM && !d.isAfter(period.end)
    }
