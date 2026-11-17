// Orientierungszeilen je Steuervorlage der Jahresübersicht — Teil von TaxYearSummaryBuilder.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Orientierung je Vorlage. */
internal fun TaxYearSummaryBuilder.orientation() {
    when (template) {
        TaxTemplate.DE -> deOrientation()
        TaxTemplate.AT -> atOrientation()
        TaxTemplate.CH -> chOrientation()
        TaxTemplate.UK -> ukOrientation()
        TaxTemplate.US -> lines += TaxSummaryLine(TaxLine.US_TERMS)
        TaxTemplate.CA -> caOrientation()
        TaxTemplate.AU -> auOrientation()
        TaxTemplate.FR -> frOrientation()
        TaxTemplate.JP -> lines += TaxSummaryLine(
            TaxLine.JP_MISC,
            net.usdt + incUsdt,
            if (fx) net.target + incTarget else null,
            incomplete = netIncomplete || incMissing,
            noFx = netFx || incFx,
        )
        TaxTemplate.NEUTRAL -> Unit
    }
}

internal fun TaxYearSummaryBuilder.deOrientation() {
    val l = Acc(long, all, fx)
    lines += l.line(TaxLine.DE_LONG, fx)
    val sh = Acc(short, all, fx)
    val limit = TaxExport.privateSalesLimitEur(startYear)
    val salesStatus = when {
        !fx || sh.unknown > 0 || unclassifiedRows.isNotEmpty() || sh.fxMissing > 0 -> TaxLimitStatus.UNKNOWN
        // Netto-Verlust: eigener Status (nur mit Gewinnen aus privaten Veräusserungsgeschäften verrechenbar)
        sh.target.signum() < 0 -> TaxLimitStatus.LOSS
        sh.target < limit -> TaxLimitStatus.BELOW
        else -> TaxLimitStatus.REACHED
    }
    lines += TaxSummaryLine(TaxLine.DE_PRIVATE_LIMIT, null, limit, salesStatus)
    val incomeStatus = when {
        !fx || incMissing || incFx -> TaxLimitStatus.UNKNOWN
        incTarget < TaxExport.INCOME_LIMIT_EUR -> TaxLimitStatus.BELOW
        else -> TaxLimitStatus.REACHED
    }
    // Geschätzter Tageskurs in einer gezählten Einkunft: Vermerk an der 256-€-Grenze
    val incEstimated = income.any { it.estimated && it.valueUsdt != null }
    lines += TaxSummaryLine(TaxLine.DE_INCOME_LIMIT, null, TaxExport.INCOME_LIMIT_EUR, incomeStatus, estimated = incEstimated)
}

internal fun TaxYearSummaryBuilder.atOrientation() {
    // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
    // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG); Vermerk, wenn Belohnungen vor dem 1.3.2022 mit
    // Anschaffungskosten 0 im Neubestand stecken (Rechtslage dafür nicht ausdrücklich geklärt)
    lines += TaxSummaryLine(
        TaxLine.AT_RATE,
        net.usdt + intUsdt,
        if (fx) net.target + intTarget else null,
        incomplete = netIncomplete || intMissing,
        noFx = netFx || intFx,
        note = TaxSummaryNote.AT_STAKING_ZERO_COST.takeIf { TaxExport.atEarlyRewards(trades, zone, period) },
    )
    if (atOld.isNotEmpty()) {
        lines += Acc(atOld.filter { it.longTerm == true }, all, fx).line(TaxLine.AT_OLD_LONG, fx)
    }
    if (atOld.isNotEmpty() || atEarlyNew.isNotEmpty()) {
        // Spekulationsgeschäfte (Altbestand ≤ 1 Jahr, Neuvermögen verkauft vor dem 1.3.2022): Freigrenze 440 €
        // (§ 31 Abs. 3 EStG a. F.) — wie die Freigrenze § 23 in DE nicht prüfbar, sobald ein Gewinn, ein
        // Devisenkurs oder beim Altbestand die Haltedauer fehlt; Neuvermögen vor dem 1.3.2022 ist immer ≤ 1 Jahr
        val spec = Acc(atOld.filter { it.longTerm == false } + atEarlyNew, all, fx)
        val specStatus = when {
            !fx || spec.unknown > 0 || spec.fxMissing > 0 || atOld.any { it.longTerm == null } -> TaxLimitStatus.UNKNOWN
            spec.target.signum() < 0 -> TaxLimitStatus.LOSS
            spec.target <= TaxExport.AT_SPECULATION_LIMIT_EUR -> TaxLimitStatus.BELOW
            else -> TaxLimitStatus.REACHED
        }
        lines += TaxSummaryLine(
            TaxLine.AT_OLD_SPECULATION_LIMIT,
            null,
            TaxExport.AT_SPECULATION_LIMIT_EUR,
            specStatus,
            note = TaxSummaryNote.AT_WITHOUT_OPTION.takeIf { atOption },
        )
    }
}

internal fun TaxYearSummaryBuilder.chOrientation() {
    // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
    lines += TaxSummaryLine(TaxLine.CH_PRIVATE)
    lines += TaxSummaryLine(TaxLine.CH_INCOME, incUsdt, incTarget.takeIf { fx }, incomplete = incMissing, noFx = incFx)
    // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»; der Wert per
    // 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, keine ESTV-Kurse (Vermerk)
    lines += TaxSummaryLine(TaxLine.CH_WEALTH, notCalculated = true, note = TaxSummaryNote.CH_EXCHANGE_VALUE)
}

internal fun TaxYearSummaryBuilder.ukOrientation() {
    val aea = TaxTemplate.ukAnnualExemptGbp(startYear)
    lines += TaxSummaryLine(TaxLine.UK_EXEMPT, null, aea, status = if (aea == null) TaxLimitStatus.UNKNOWN else null)
    val after = if (aea != null && fx) net.target.subtract(aea).max(zero) else null
    lines += TaxSummaryLine(TaxLine.UK_AFTER_EXEMPT, null, after, incomplete = netIncomplete, noFx = netFx)
}

internal fun TaxYearSummaryBuilder.caOrientation() {
    // Nur in CAD (Gewinn/Verlust ist in CAD entschieden, Entscheid des Inhabers): keine USDT-Spalte. Netto ein
    // Verlust → steuerbar 0, der abziehbare Teil (× 1/2) ist als «net capital loss» vorzutragen (CRA)
    val f = TaxTemplate.CA_INCLUSION
    val taxable = if (fx) scaled(net.target + deniedTarget, f) else null
    lines += TaxSummaryLine(
        TaxLine.CA_TAXABLE,
        null,
        taxable?.max(zero),
        incomplete = netIncomplete,
        noFx = !fx || netFx || deniedFx,
    )
    if (taxable != null && taxable.signum() < 0) {
        lines += TaxSummaryLine(
            TaxLine.NET_CAPITAL_LOSS,
            null,
            taxable.negate(),
            incomplete = netIncomplete,
            noFx = netFx || deniedFx,
        )
    }
}

internal fun TaxYearSummaryBuilder.auOrientation() {
    // Verluste zuerst gegen Gewinne ohne Rabatt (≤ 12 Monate, ohne Haltedauer), dann gegen die übrigen; 50 % Rabatt
    fun discounted(pick: (TaxGainRow) -> BigDecimal?): BigDecimal {
        var nonDisc = zero
        var disc = zero
        var losses = zero
        for (r in rows) {
            val g = pick(r) ?: continue
            when {
                g.signum() <= 0 -> losses -= g
                r.longTerm == true -> disc += g
                else -> nonDisc += g
            }
        }
        var rest = nonDisc - losses
        if (rest.signum() < 0) {
            disc += rest
            rest = zero
        }
        return if (disc.signum() < 0) disc else rest + disc.multiply(BigDecimal.ONE - TaxTemplate.AU_DISCOUNT, PortfolioDecimal.MC)
    }
    // Netto ein Verlust: steuerbar 0, der ganze Verlust ist vorzutragen (ATO «net capital loss», ohne Rabatt)
    val u = discounted { it.gainUsdt }
    val t = if (fx) discounted { it.gainTarget } else null
    lines += TaxSummaryLine(TaxLine.AU_DISCOUNTED, u.max(zero), t?.max(zero), incomplete = netIncomplete, noFx = netFx)
    if ((if (fx) t else u)?.signum() == -1) {
        lines += TaxSummaryLine(
            TaxLine.NET_CAPITAL_LOSS,
            u.negate().max(zero),
            t?.negate()?.max(zero),
            incomplete = netIncomplete,
            noFx = netFx,
        )
    }
}

internal fun TaxYearSummaryBuilder.frOrientation() {
    val status = when {
        !fx || procMissing || procFx -> TaxLimitStatus.UNKNOWN
        procTarget <= TaxTemplate.FR_EXEMPT_EUR -> TaxLimitStatus.BELOW
        else -> TaxLimitStatus.REACHED
    }
    lines += TaxSummaryLine(TaxLine.FR_THRESHOLD, null, TaxTemplate.FR_EXEMPT_EUR, status)
    lines += TaxSummaryLine(TaxLine.FR_RATE)
}
