package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Jahresübersicht je Steuer-Vorlage ([TaxExport.yearSummary]): Posten, Freigrenzen, Orientierung.

/** Ergebnis einer Freigrenzen-Prüfung ([TaxSummaryLine.status]). */
enum class TaxLimitStatus {
    /** Unter der Freigrenze (steuerfrei). */
    BELOW,
    /** Freigrenze erreicht oder überschritten: der GANZE Betrag ist steuerpflichtig (Freigrenze, kein Freibetrag). */
    REACHED,
    /** Nicht prüfbar: EUR-Werte fehlen, ein Gewinn/Wert ist unbekannt oder die Haltedauer fehlt (Ø). */
    UNKNOWN,
    /**
     * DE § 23: netto ein Verlust — kein steuerpflichtiger Gewinn; der Verlust ist nur mit Gewinnen aus privaten
     * Veräusserungsgeschäften verrechenbar (§ 23 Abs. 3 Sätze 7–8 EStG, Rück-/Vortrag nach § 10d).
     */
    LOSS,
}

/** Zusatz-Vermerk eines Postens der Jahresübersicht ([TaxSummaryLine.note], Text in [TaxSummaryTexts.notes]). */
enum class TaxSummaryNote {
    /**
     * AT Einkünfte: nur Information, nicht addieren — Belohnungen (Staking, Airdrop) haben im Neubestand Anschaffungskosten 0
     * und sind erst beim Verkauf steuerpflichtig (dort schon in den Gewinnen enthalten; BMF, § 27b Abs. 2 EStG).
     */
    AT_REWARD_INFO,
    /** CH Vermögen: der Wert per 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, nicht die Steuerwerte der ESTV-Kursliste. */
    CH_EXCHANGE_VALUE,
    /**
     * CH Gewinne/Verluste/netto: nur Information — FIFO-Rechnung der App, keine Einordnung (Kapitalgewinne im
     * Privatvermögen «nicht gerechnet – siehe ESTV»).
     */
    CH_FIFO_INFO,
    /**
     * DE Auszahlungsgebühren ([TaxLine.WITHDRAWAL_FEES]): nicht mit § 23 verrechnet (keine Veräusserung, mindern weder
     * die Summe ≤ 1 Jahr noch die Freigrenze) — ggf. als Werbungskosten prüfen.
     */
    DE_WITHDRAWAL_FEE,
    /**
     * AT Auszahlungsgebühren: nicht mit dem Neubestand verrechnet — beim Sondersteuersatz 27,5 % sind Aufwendungen nicht
     * abzugsfähig (§ 20 Abs. 2 EStG).
     */
    AT_WITHDRAWAL_FEE,
    /**
     * AT Zinsen ([TaxLine.AT_INTEREST]): beim Zufluss laufende Einkünfte aus Kryptowährungen (Entgelt für die Überlassung,
     * § 27b Abs. 2 Z 1 EStG) zum Sondersteuersatz 27,5 %; die Coins behalten diesen Wert als Anschaffungskosten (BMF).
     */
    AT_INTEREST_INCOME,
    /**
     * UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat ([TaxTemplate.quoteFeeIsInfo]): kein Verlust, nur Information
     * (UK: HMRC CRYPTO22150, keine «allowable cost»; sonst keine Regel, die sie abziehbar macht).
     */
    WITHDRAWAL_FEE_INFO,
    /**
     * UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat ([TaxLine.TRANSFER_FEES], Build 57): kein Verlust und nicht im
     * Einstand, nur Information.
     */
    TRANSFER_FEE_INFO,
    /**
     * AT Neubestand netto ([TaxLine.NEW_NET]): Verkäufe bzw. Tausch von Neuvermögen vom 1.3.2021 ([TaxTemplate.AT_NEW_FROM])
     * bis 28.2.2022 (vor [TaxTemplate.AT_SWAP_NEUTRAL_FROM]) sind mit 27,5 % gebucht — rechtlich galt dort noch die alte
     * Rechtslage (§ 31 EStG a. F.) mit Option auf 27,5 % (§ 124b Z 384 EStG): mit Steuerberatung klären.
     */
    AT_TRANSITION_2021,
}

/**
 * Posten der Jahresübersicht einer Vorlage ([TaxExport.yearSummary]); die App nennt sie übersetzt. Tatsachen-Posten
 * (Summen) zuerst; [orientation] = Schluss nach den Regeln der Vorlage, nur als «Orientierung» mit Hinweis.
 */
enum class TaxLine(val orientation: Boolean = false) {
    SHORT_GAINS, SHORT_LOSSES, SHORT_NET, LONG_GAINS, LONG_LOSSES, LONG_NET, UNCLASSIFIED,
    GAINS, LOSSES, NET,
    /** AT: Neubestand netto, Altbestand ≤ 1 Jahr / > 1 Jahr netto. */
    NEW_NET, OLD_SHORT_NET, OLD_LONG_NET,
    /** CA: nicht abziehbare Verluste (Superficial Loss). */
    DENIED_LOSS,
    /** Erlöse (FR: Abtretungspreise vor Gebühren). */
    PROCEEDS,
    INCOME,
    /**
     * AT: Zinsen aus Lending ([TaxIncomeRow.interest]) zum Wert beim Zufluss — zählen (Vermerk [TaxSummaryNote.AT_INTEREST_INCOME]);
     * nicht in [INCOME] (dort nur Belohnungen mit Anschaffungskosten 0, Information).
     */
    AT_INTEREST,
    /** FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe ([TaxOutflow]); das Jahr gilt als unvollständig. */
    FR_OUTFLOW,
    /**
     * DE, AT, UK, US, CA, AU, JP: Gebühren von Auszahlungen ([PortfolioFeeExpense.WITHDRAWAL]) — eigener Posten, nicht mit
     * den Veräusserungen verrechnet (Vermerk [TaxSummaryNote.DE_WITHDRAWAL_FEE], [TaxSummaryNote.AT_WITHDRAWAL_FEE] bzw.
     * [TaxSummaryNote.WITHDRAWAL_FEE_INFO]).
     */
    WITHDRAWAL_FEES,
    /**
     * UK, US, CA, AU, JP (Build 57): Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets ([PortfolioFeeExpense.TRANSFER])
     * — nur Information (Vermerk [TaxSummaryNote.TRANSFER_FEE_INFO]), weder Verlust noch Einstand.
     */
    TRANSFER_FEES,
    DE_LONG(true), DE_PRIVATE_LIMIT(true), DE_INCOME_LIMIT(true),
    AT_RATE(true), AT_OLD_LONG(true),
    /**
     * AT: Altbestand ≤ 1 Jahr gehalten = Spekulationsgeschäft nach alter Rechtslage (§ 30 EStG a. F.) — Freigrenze 440 €
     * im Kalenderjahr (§ 31 Abs. 3 EStG a. F.: steuerfrei, wenn höchstens 440 €), Status wie [DE_PRIVATE_LIMIT].
     */
    AT_OLD_SPECULATION_LIMIT(true),
    CH_PRIVATE(true), CH_INCOME(true), CH_WEALTH(true),
    UK_EXEMPT(true), UK_AFTER_EXEMPT(true),
    US_TERMS(true),
    CA_TAXABLE(true),
    AU_DISCOUNTED(true),
    FR_THRESHOLD(true), FR_RATE(true),
    JP_MISC(true),
}

/** Ein Posten: Beträge (USDT / Steuerwährung; null = keiner bzw. nicht bekannt), Grenze, Vermerke. */
data class TaxSummaryLine(
    val line: TaxLine,
    val usdt: BigDecimal? = null,
    val target: BigDecimal? = null,
    val status: TaxLimitStatus? = null,
    /** Ein Gewinn/Wert fehlt (Kaufpreis, Erlös, Haltedauer) — Betrag nur aus dem Bekannten. */
    val incomplete: Boolean = false,
    /** Ein Devisenkurs fehlt — Betrag in der Steuerwährung nur aus dem Bekannten. */
    val noFx: Boolean = false,
    /** Kein Betrag, weil die App ihn nicht rechnet (CH Vermögenssteuer: ESTV-Kursliste) — Vermerk [TaxSummaryTexts.notCalculated]. */
    val notCalculated: Boolean = false,
    /** Zusatz-Vermerk ([TaxSummaryTexts.notes]); null = keiner. */
    val note: TaxSummaryNote? = null,
)

/** Jahresübersicht einer Vorlage — ORIENTIERUNG, keine Steuerberatung. */
data class TaxYearSummary(
    val template: TaxTemplate,
    val method: TaxMethod,
    val period: TaxPeriod,
    /** Steuerwährung ([TaxTemplate.currency], Neutral: Anzeigewährung). */
    val currency: String,
    /** Devisenkurse geladen (oder USD). */
    val fxAvailable: Boolean,
    val lines: List<TaxSummaryLine>,
    /** US-Übergang, mit dem gerechnet wurde ([TaxTemplate.walletFrom]); null = keiner. */
    val walletFrom: LocalDate? = null,
)

/** Texte der Jahresübersicht ([TaxExport.summaryCsv]); [labels] je Posten. */
data class TaxSummaryTexts(
    val title: String,
    val disclaimer: String,
    val item: String,
    val usdt: String,
    /** Spalte der Steuerwährung, z. B. «GBP». */
    val target: String,
    val note: String,
    /** «Orientierung». */
    val orientation: String,
    val labels: Map<TaxLine, String>,
    val below: String,
    val reached: String,
    val unknown: String,
    val incomplete: String,
    val noFx: String,
    /** Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = [orientation]. */
    val orientationHeading: String = "",
    /** Posten im Orientierungs-Block «Orientierung: %s»; leer = «[orientation]: Posten». */
    val orientationItem: String = "",
    /** Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» ([TaxSummaryLine.notCalculated]); leer = keiner. */
    val notCalculated: String = "",
    /** Status «Verlust» ([TaxLimitStatus.LOSS]); leer = [below]. */
    val loss: String = "",
    /** Texte der Zusatz-Vermerke ([TaxSummaryLine.note]); fehlt einer = kein Vermerk. */
    val notes: Map<TaxSummaryNote, String> = emptyMap(),
)

/**
 * Posten im Orientierungs-Block: [item] mit «%s» für [label]; ohne [item] «[fallback]: [label]» (leer = nur [label]).
 * Jeder Schluss nach den Regeln einer Vorlage steht so sichtbar als Orientierung da (CSV und Karte).
 */
fun taxOrientationLabel(item: String, fallback: String, label: String): String = when {
    item.contains("%s") -> item.replace("%s", label)
    fallback.isNotEmpty() -> "$fallback: $label"
    else -> label
}

/**
 * Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
 * Einstand (FR: Erwerbspreis-Summe nicht gesichert, [TaxGainRow.costIncomplete]), Anzahl ohne Devisenkurs.
 */
private class Acc(rows: List<TaxGainRow>, pick: (BigDecimal) -> Boolean, fx: Boolean) {
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
 * Jahresübersicht der Vorlage [template] für das Steuerjahr ab [startYear] — ORIENTIERUNG, KEINE STEUERBERATUNG.
 * Aus den Zeilen ([gainRows] nach [method], in [currency] mit [rates]; null = keine Kurse) und den Einkünften:
 *  - Tatsachen: Gewinne (> 0), Verluste (≤ 0) und netto — mit Haltedauer-Gruppen ≤ 1 / > 1 Jahr bei DE, US, AU und
 *    Neutral (ohne Haltedauer: eigener Posten), AT getrennt nach Neubestand und Altbestand ≤ / > 1 Jahr; CA nicht
 *    abziehbare Verluste; Erlöse (FR: Abtretungspreise vor Gebühren); Einkünfte (Belohnungen zum Wert beim Erhalt).
 *  - AT Einkünfte: Belohnungen nur Information ([TaxSummaryNote.AT_REWARD_INFO]) — sie stecken mit Anschaffungskosten 0
 *    im Neubestand und zählen erst beim Verkauf (kein Doppel aus Zufluss und Verkaufsgewinn); Zinsen aus Lending
 *    ([TaxLine.AT_INTEREST]) sind dagegen beim Zufluss Einkünfte (§ 27b Abs. 2 Z 1 EStG) und zählen.
 *  - Orientierung ([TaxLine.orientation]): DE Freigrenzen § 23 (netto ≤ 1 Jahr < Grenze; netto ein Verlust: eigener
 *    Status [TaxLimitStatus.LOSS]) und § 22 Nr. 3 (< 256 €),
 *    > 1 Jahr nicht steuerbar; AT 27,5 % auf Neubestand netto + Zinsen (Verlustausgleich innerhalb der Einkünfte zum
 *    Sondersteuersatz, § 27 Abs. 8 EStG), Altbestand > 1 Jahr nicht steuerbar; CH ohne Einordnung:
 *    Kapitalgewinne «nicht gerechnet – siehe ESTV» (kein Betrag), Belohnungen aufgeführt (Betrag, Behandlung siehe
 *    ESTV-Arbeitspapier), Vermögenssteuer «nicht gerechnet» (ESTV-Kursliste; Vermerk: Bestand-CSV per 31.12. =
 *    Börsenkurs × USD/CHF, [TaxSummaryNote.CH_EXCHANGE_VALUE]); UK Annual Exempt Amount und netto darüber;
 *    US kurz-/langfristig; CA steuerbarer Anteil (netto + nicht abziehbar) × 1/2; AU Verluste zuerst gegen Gewinne
 *    ≤ 12 Monate, dann 50 % Rabatt auf den Rest > 12 Monate; FR Abtretungen ≤ 305 € befreit (sonst ganz steuerbar);
 *    JP sonstige Einkünfte = netto + Einkünfte.
 * Gezählt wird nur Bekanntes; was fehlt, steht als «unvollständig» bzw. «ohne Devisenkurs» am Posten (auch ein Gewinn
 * aus unvollständigem Einstand, FR). Gewinn oder Verlust entscheidet die Steuerwährung (ohne Kurs USDT). CH: Gewinne,
 * Verluste, netto nur als Information ([TaxSummaryNote.CH_FIFO_INFO]).
 */
fun TaxExport.yearSummary(
    trades: List<PortfolioTrade>,
    template: TaxTemplate,
    method: TaxMethod,
    startYear: Int,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
    priceAt: ((String, Long) -> BigDecimal?)? = null,
    /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
    walletFrom: LocalDate? = null,
    /** USDT/USD-Tagesschluss je Datum ([rateOn]); null = USDT gilt 1 USD. */
    usdtUsd: Map<Long, BigDecimal>? = null,
): TaxYearSummary {
    val zero = BigDecimal.ZERO
    val period = template.period(startYear)
    val fx = rates != null || currency.trim().uppercase() == "USD"
    val allRows = gainRows(
        trades, method, period, zone, currency, rates ?: emptyMap(), priceAt, rules = template.eventRules,
        walletFrom = walletFrom, usdtUsd = usdtUsd,
    )
    // DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten, nicht in den Summen (DE: § 23 und Freigrenze;
    // AT: Aufwendungen beim Sondersteuersatz nicht abzugsfähig, § 20 Abs. 2 EStG); UK, US, CA, AU, JP: kein Verlust, nur
    // Information ([TaxTemplate.quoteFeeIsInfo])
    val feeApart = template == TaxTemplate.DE || template == TaxTemplate.AT || template.quoteFeeIsInfo
    val (withdrawalFees, sales) = if (feeApart) allRows.partition { it.feeExpense == PortfolioFeeExpense.WITHDRAWAL }
    else emptyList<TaxGainRow>() to allRows
    // Umbuchungsgebühren nur zur Information ([PortfolioFeeExpense.TRANSFER], Build 57): eigener Posten
    val (transferFees, rows) = sales.partition { it.feeExpense == PortfolioFeeExpense.TRANSFER }
    // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
    val (interest, income) = incomeRows(trades, period, zone, currency, rates ?: emptyMap(), priceAt, usdtUsd)
        .partition { template == TaxTemplate.AT && it.interest }
    val pos: (BigDecimal) -> Boolean = { it.signum() > 0 }
    val neg: (BigDecimal) -> Boolean = { it.signum() <= 0 }
    val all: (BigDecimal) -> Boolean = { true }
    val lines = ArrayList<TaxSummaryLine>()
    val net: Acc
    val short = rows.filter { it.longTerm == false }
    val long = rows.filter { it.longTerm == true }
    val unclassifiedRows = rows.filter { it.longTerm == null }
    if (template.holdingBuckets) {
        lines += Acc(short, pos, fx).line(TaxLine.SHORT_GAINS, fx, false)
        lines += Acc(short, neg, fx).line(TaxLine.SHORT_LOSSES, fx, false)
        lines += Acc(short, all, fx).line(TaxLine.SHORT_NET, fx)
        lines += Acc(long, pos, fx).line(TaxLine.LONG_GAINS, fx, false)
        lines += Acc(long, neg, fx).line(TaxLine.LONG_LOSSES, fx, false)
        lines += Acc(long, all, fx).line(TaxLine.LONG_NET, fx)
        if (unclassifiedRows.isNotEmpty()) lines += Acc(unclassifiedRows, all, fx).line(TaxLine.UNCLASSIFIED, fx)
        net = Acc(rows, all, fx)
    } else if (template == TaxTemplate.AT) {
        val old = rows.filter { it.oldHolding == true }
        net = Acc(rows.filter { it.oldHolding != true }, all, fx)
        lines += Acc(rows.filter { it.oldHolding != true }, pos, fx).line(TaxLine.GAINS, fx, false)
        lines += Acc(rows.filter { it.oldHolding != true }, neg, fx).line(TaxLine.LOSSES, fx, false)
        // Verkäufe/Tausch von Neuvermögen 1.3.2021–28.2.2022: alte Rechtslage mit Option auf 27,5 % — Vermerk am Posten
        val transition = rows.any {
            it.oldHolding != true && it.soldDate >= TaxTemplate.AT_NEW_FROM && it.soldDate < TaxTemplate.AT_SWAP_NEUTRAL_FROM
        }
        lines += net.line(TaxLine.NEW_NET, fx, note = TaxSummaryNote.AT_TRANSITION_2021.takeIf { transition })
        if (old.isNotEmpty()) {
            lines += Acc(old.filter { it.longTerm != true }, all, fx).line(TaxLine.OLD_SHORT_NET, fx)
            lines += Acc(old.filter { it.longTerm == true }, all, fx).line(TaxLine.OLD_LONG_NET, fx)
        }
    } else {
        net = Acc(rows, all, fx)
        // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
        // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
        val info = TaxSummaryNote.CH_FIFO_INFO.takeIf { template == TaxTemplate.CH }
        lines += Acc(rows, pos, fx).line(TaxLine.GAINS, fx, false, info)
        lines += Acc(rows, neg, fx).line(TaxLine.LOSSES, fx, false, info)
        lines += net.line(TaxLine.NET, fx, note = info)
    }
    if (withdrawalFees.isNotEmpty()) {
        val note = when (template) {
            TaxTemplate.AT -> TaxSummaryNote.AT_WITHDRAWAL_FEE
            TaxTemplate.DE -> TaxSummaryNote.DE_WITHDRAWAL_FEE
            else -> TaxSummaryNote.WITHDRAWAL_FEE_INFO
        }
        lines += Acc(withdrawalFees, all, fx).line(TaxLine.WITHDRAWAL_FEES, fx, note = note)
    }
    if (transferFees.isNotEmpty()) {
        lines += Acc(transferFees, all, fx).line(TaxLine.TRANSFER_FEES, fx, note = TaxSummaryNote.TRANSFER_FEE_INFO)
    }
    // CA: nicht abziehbare Verluste
    val adjRows = rows.filter { it.adjustmentUsdt != null }
    val deniedUsdt = adjRows.fold(zero) { a, r -> a + r.adjustmentUsdt!! }
    val deniedTarget = adjRows.fold(zero) { a, r -> a + (r.adjustmentTarget ?: zero) }
    val deniedFx = fx && adjRows.any { it.adjustmentTarget == null }
    if (adjRows.isNotEmpty()) lines += TaxSummaryLine(TaxLine.DENIED_LOSS, deniedUsdt, deniedTarget.takeIf { fx }, noFx = deniedFx)
    // Erlöse (FR: vor Gebühren); ohne Schenkungen an Ehe-/Lebenspartner — ihr «Erlös» ist nur der übergehende Einstand,
    // kein Verkaufserlös (UK s. 58, CA 73(1))
    val grossFr = template == TaxTemplate.FR
    val sold = rows.filter { !it.spouseGift }
    val procUsdt = sold.fold(zero) { a, r -> a + ((if (grossFr) r.grossProceedsUsdt else r.proceedsUsdt) ?: zero) }
    val procTarget = sold.fold(zero) { a, r -> a + ((if (grossFr) r.grossProceedsTarget else r.proceedsTarget) ?: zero) }
    val procMissing = sold.any { (if (grossFr) it.grossProceedsUsdt else it.proceedsUsdt) == null }
    val procFx = fx && sold.any { r ->
        (if (grossFr) r.grossProceedsUsdt else r.proceedsUsdt) != null && (if (grossFr) r.grossProceedsTarget else r.proceedsTarget) == null
    }
    lines += TaxSummaryLine(TaxLine.PROCEEDS, procUsdt, procTarget.takeIf { fx }, incomplete = procMissing, noFx = procFx)
    // Einkünfte
    val incUsdt = income.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) }
    val incTarget = income.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }
    val incMissing = income.any { it.valueMissing }
    val incFx = fx && income.any { it.fxMissing }
    // AT: Belohnungen im Neubestand mit Anschaffungskosten 0 (kein Zufluss besteuert, erst der Verkauf) — der Posten ist
    // nur Information und zählt in keine Summe (sonst doppelt: Zufluss + Verkaufsgewinn mit AK 0)
    lines += TaxSummaryLine(
        TaxLine.INCOME, incUsdt, incTarget.takeIf { fx }, incomplete = incMissing, noFx = incFx,
        note = TaxSummaryNote.AT_REWARD_INFO.takeIf { template == TaxTemplate.AT },
    )
    // AT: Zinsen aus Lending beim Zufluss (§ 27b Abs. 2 Z 1 EStG, 27,5 %) — zählen, Anschaffungskosten = dieser Wert
    val intUsdt = interest.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) }
    val intTarget = interest.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }
    val intMissing = interest.any { it.valueMissing }
    val intFx = fx && interest.any { it.fxMissing }
    if (interest.isNotEmpty()) {
        lines += TaxSummaryLine(
            TaxLine.AT_INTEREST, intUsdt, intTarget.takeIf { fx }, incomplete = intMissing, noFx = intFx,
            note = TaxSummaryNote.AT_INTEREST_INCOME,
        )
    }
    // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»
    if (template == TaxTemplate.FR) {
        // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
        val outs = TaxEngines.outflows(trades.filter { it.amount.signum() > 0 }, method, priceAt).filter { localDate(it.time, zone) in period }
        if (outs.isNotEmpty()) {
            var outUsdt = zero
            var outTarget = zero
            var outFx = false
            for (o in outs) {
                val r = o.ptaReductionUsdt ?: continue
                outUsdt += r
                val rate = rateOn(rates, localDate(o.time, zone), currency, usdtUsd)
                if (rate != null) outTarget += r.multiply(rate, PortfolioDecimal.MC) else if (fx) outFx = true
            }
            lines += TaxSummaryLine(TaxLine.FR_OUTFLOW, outUsdt, outTarget.takeIf { fx }, incomplete = true, noFx = outFx)
        }
    }
    val netIncomplete = net.incomplete
    val netFx = net.fxMissing > 0
    fun scaled(v: BigDecimal, f: BigDecimal): BigDecimal = v.multiply(f, PortfolioDecimal.MC)
    // Orientierung je Vorlage
    when (template) {
        TaxTemplate.DE -> {
            val l = Acc(long, all, fx)
            lines += l.line(TaxLine.DE_LONG, fx)
            val sh = Acc(short, all, fx)
            val limit = privateSalesLimitEur(startYear)
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
                incTarget < INCOME_LIMIT_EUR -> TaxLimitStatus.BELOW
                else -> TaxLimitStatus.REACHED
            }
            lines += TaxSummaryLine(TaxLine.DE_INCOME_LIMIT, null, INCOME_LIMIT_EUR, incomeStatus)
        }
        TaxTemplate.AT -> {
            // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
            // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG)
            lines += TaxSummaryLine(
                TaxLine.AT_RATE, net.usdt + intUsdt, if (fx) net.target + intTarget else null,
                incomplete = netIncomplete || intMissing, noFx = netFx || intFx,
            )
            if (rows.any { it.oldHolding == true }) {
                lines += Acc(rows.filter { it.oldHolding == true && it.longTerm == true }, all, fx).line(TaxLine.AT_OLD_LONG, fx)
                // Altbestand ≤ 1 Jahr: Freigrenze 440 € (§ 31 Abs. 3 EStG a. F.) — wie die Freigrenze § 23 in DE nicht
                // prüfbar, sobald ein Gewinn, ein Devisenkurs oder die Haltedauer fehlt
                val old = rows.filter { it.oldHolding == true }
                val spec = Acc(old.filter { it.longTerm == false }, all, fx)
                val specStatus = when {
                    !fx || spec.unknown > 0 || spec.fxMissing > 0 || old.any { it.longTerm == null } -> TaxLimitStatus.UNKNOWN
                    spec.target.signum() < 0 -> TaxLimitStatus.LOSS
                    spec.target <= AT_SPECULATION_LIMIT_EUR -> TaxLimitStatus.BELOW
                    else -> TaxLimitStatus.REACHED
                }
                lines += TaxSummaryLine(TaxLine.AT_OLD_SPECULATION_LIMIT, null, AT_SPECULATION_LIMIT_EUR, specStatus)
            }
        }
        TaxTemplate.CH -> {
            // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
            lines += TaxSummaryLine(TaxLine.CH_PRIVATE)
            lines += TaxSummaryLine(TaxLine.CH_INCOME, incUsdt, incTarget.takeIf { fx }, incomplete = incMissing, noFx = incFx)
            // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»; der Wert per
            // 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, keine ESTV-Kurse (Vermerk)
            lines += TaxSummaryLine(TaxLine.CH_WEALTH, notCalculated = true, note = TaxSummaryNote.CH_EXCHANGE_VALUE)
        }
        TaxTemplate.UK -> {
            val aea = TaxTemplate.ukAnnualExemptGbp(startYear)
            lines += TaxSummaryLine(TaxLine.UK_EXEMPT, null, aea, status = if (aea == null) TaxLimitStatus.UNKNOWN else null)
            val after = if (aea != null && fx) net.target.subtract(aea).max(zero) else null
            lines += TaxSummaryLine(TaxLine.UK_AFTER_EXEMPT, null, after, incomplete = netIncomplete, noFx = netFx)
        }
        TaxTemplate.US -> lines += TaxSummaryLine(TaxLine.US_TERMS)
        TaxTemplate.CA -> {
            val f = TaxTemplate.CA_INCLUSION
            lines += TaxSummaryLine(
                TaxLine.CA_TAXABLE, scaled(net.usdt + deniedUsdt, f), if (fx) scaled(net.target + deniedTarget, f) else null,
                incomplete = netIncomplete, noFx = netFx || deniedFx,
            )
        }
        TaxTemplate.AU -> {
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
            lines += TaxSummaryLine(
                TaxLine.AU_DISCOUNTED, discounted { it.gainUsdt }, if (fx) discounted { it.gainTarget } else null,
                incomplete = netIncomplete, noFx = netFx,
            )
        }
        TaxTemplate.FR -> {
            val status = when {
                !fx || procMissing || procFx -> TaxLimitStatus.UNKNOWN
                procTarget <= TaxTemplate.FR_EXEMPT_EUR -> TaxLimitStatus.BELOW
                else -> TaxLimitStatus.REACHED
            }
            lines += TaxSummaryLine(TaxLine.FR_THRESHOLD, null, TaxTemplate.FR_EXEMPT_EUR, status)
            lines += TaxSummaryLine(TaxLine.FR_RATE)
        }
        TaxTemplate.JP -> lines += TaxSummaryLine(
            TaxLine.JP_MISC, net.usdt + incUsdt, if (fx) net.target + incTarget else null,
            incomplete = netIncomplete || incMissing, noFx = netFx || incFx,
        )
        TaxTemplate.NEUTRAL -> Unit
    }
    return TaxYearSummary(template, method, period, currency, fx, lines, walletFrom)
}
