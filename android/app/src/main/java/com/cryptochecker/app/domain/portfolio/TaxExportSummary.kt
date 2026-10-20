package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Jahresübersicht je Steuer-Vorlage ([TaxExport.yearSummary]): Posten, Freigrenzen, Orientierung. Modell in
// TaxSummaryModel.kt, Texte in TaxSummaryTexts.kt, Teile (Summen, FR-Abgänge) in TaxExportSummaryHelpers.kt.


/**
 * Jahresübersicht der Vorlage [template] für das Steuerjahr ab [startYear] — ORIENTIERUNG, KEINE STEUERBERATUNG.
 * Aus den Zeilen ([gainRows] nach [method], in [currency] mit [rates]; null = keine Kurse) und den Einkünften:
 *  - Tatsachen: Gewinne (> 0), Verluste (≤ 0) und netto — mit Haltedauer-Gruppen ≤ 1 / > 1 Jahr bei DE, US, AU und
 *    Neutral (ohne Haltedauer: eigener Posten), AT getrennt nach Neubestand (verkauft ab 1.3.2022), Spekulationsgeschäften
 *    nach alter Rechtslage (Altbestand ≤ 1 Jahr und Neuvermögen verkauft vor dem 1.3.2022) und Altbestand > 1 Jahr; CA
 *    nicht abziehbare Verluste; Erlöse (FR: Abtretungspreise vor Gebühren); Einkünfte (Belohnungen zum Wert beim Erhalt).
 *  - AT Einkünfte: Belohnungen nur Information ([TaxSummaryNote.AT_REWARD_INFO]) — sie stecken mit Anschaffungskosten 0
 *    im Neubestand und zählen erst beim Verkauf (kein Doppel aus Zufluss und Verkaufsgewinn); Zinsen aus Lending
 *    ([TaxLine.AT_INTEREST]) sind dagegen beim Zufluss Einkünfte (§ 27b Abs. 2 Z 1 EStG) und zählen.
 *  - Orientierung ([TaxLine.orientation]): DE Freigrenzen § 23 (netto ≤ 1 Jahr < Grenze; netto ein Verlust: eigener
 *    Status [TaxLimitStatus.LOSS]) und § 22 Nr. 3 (< 256 €),
 *    > 1 Jahr nicht steuerbar; AT 27,5 % auf Neubestand netto + Zinsen (Verlustausgleich innerhalb der Einkünfte zum
 *    Sondersteuersatz, § 27 Abs. 8 EStG), Altbestand > 1 Jahr nicht steuerbar, Freigrenze 440 € für die
 *    Spekulationsgeschäfte (ohne Option auf 27,5 % für 1.1.–28.2.2022); CH ohne Einordnung:
 *    Kapitalgewinne «nicht gerechnet – siehe ESTV» (kein Betrag), Belohnungen aufgeführt (Betrag, Behandlung siehe
 *    ESTV-Arbeitspapier), Vermögenssteuer «nicht gerechnet» (ESTV-Kursliste; Vermerk: Bestand-CSV per 31.12. =
 *    Börsenkurs × USD/CHF, [TaxSummaryNote.CH_EXCHANGE_VALUE]); davor als Posten der Vermögenswert per 31.12. zur
 *    Orientierung ([TaxLine.CH_WEALTH_VALUE], [chWealth], je Coin in [TaxYearSummary.wealth]); UK Annual Exempt Amount und netto darüber;
 *    US kurz-/langfristig; CA steuerbarer Anteil (netto + nicht abziehbar) × 1/2, nur in CAD; AU Verluste zuerst gegen
 *    Gewinne ≤ 12 Monate, dann 50 % Rabatt auf den Rest > 12 Monate; CA/AU netto ein Verlust: steuerbar 0 und
 *    «Netto-Kapitalverlust (Vortrag)»; FR Abtretungen ≤ 305 € befreit (sonst ganz steuerbar); JP sonstige Einkünfte =
 *    netto + Einkünfte. UK/CA: Fenster der 30-Tage-Regel nach dem Steuerjahr noch offen ([today] bis und mit dem
 *    letzten Tag) und eine Veräusserung (CA: mit Verlust) in den letzten 30 Tagen des Steuerjahrs → Vermerk
 *    [TaxSummaryNote.WINDOW_OPEN]; UK Erlöse vor Gebühren über der Meldegrenze → [TaxSummaryNote.UK_HMRC_REPORT];
 *    FR Abtretung mit Guthaben beobachteter Adressen im Gesamtwert → [TaxSummaryNote.FR_ADDRESS_NO_COST] am Posten netto.
 *    AT Zinsen mit Zufluss vor dem 1.3.2022: eigener Posten [TaxLine.AT_INTEREST_OLD] (alte Rechtslage), nicht 27,5 %.
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
    /** Heute (UK/CA: ist das 30-Tage-Fenster nach dem Steuerjahr noch offen?); null = heute in [zone]. */
    today: LocalDate? = null,
    /** CH: Tagesschlüsse des 31.12. ([chWealth]); null = nicht geladen → Vermögenswert «nicht gerechnet». */
    yearEndCloses: CutoffCloses? = null,
    /** CH: beobachtete Adressen am 31.12. in der Sicht der Karte ([AddressCutoff.at]); null = keine. */
    yearEndAddresses: AddressCutoffView? = null,
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
    // Information ([TaxTemplate.quoteFeeIsInfo]); FR ebenso nur Information — die Formel des art. 150 VH bis kennt nur
    // Gebühren der Abtretung selbst, eine Auszahlungsgebühr mindert den Gewinn nicht
    val feeApart = template == TaxTemplate.DE || template == TaxTemplate.AT || template == TaxTemplate.FR ||
        template.quoteFeeIsInfo
    val (withdrawalFees, sales) = if (feeApart) allRows.partition { it.feeExpense == PortfolioFeeExpense.WITHDRAWAL }
    else emptyList<TaxGainRow>() to allRows
    // Umbuchungsgebühren nur zur Information ([PortfolioFeeExpense.TRANSFER], Build 57): eigener Posten
    val (transferFees, rows) = sales.partition { it.feeExpense == PortfolioFeeExpense.TRANSFER }
    // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
    val (allInterest, income) = incomeRows(trades, period, zone, currency, rates ?: emptyMap(), priceAt, usdtUsd)
        .partition { template == TaxTemplate.AT && it.interest }
    // § 27b EStG (Sondersteuersatz) gilt für Zinsen erst mit Zufluss ab dem 1.3.2022 (§ 124b Z 384 EStG); davor alte
    // Rechtslage (Tarif) — eigener Posten, nicht in der Zeile 27,5 %
    val (interestOld, interest) = allInterest.partition { it.date < TaxTemplate.AT_SWAP_NEUTRAL_FROM }
    val pos: (BigDecimal) -> Boolean = { it.signum() > 0 }
    val neg: (BigDecimal) -> Boolean = { it.signum() <= 0 }
    val all: (BigDecimal) -> Boolean = { true }
    val lines = ArrayList<TaxSummaryLine>()
    val net: Acc
    val short = rows.filter { it.longTerm == false }
    val long = rows.filter { it.longTerm == true }
    val unclassifiedRows = rows.filter { it.longTerm == null }
    // AT: Neuvermögen, verkauft vor dem 1.3.2022, fällt noch unter die alte Rechtslage (§ 30/§ 31 EStG a. F.:
    // Spekulationsgeschäft, Tarif, Freigrenze 440 €) — die Frist von einem Jahr kann bis dahin nie abgelaufen sein. Die
    // Option auf 27,5 % gibt es nur für Realisationen vom 1.1. bis 28.2.2022 (§ 124b Z 384 lit. c EStG); ohne Antrag gilt
    // auch dort die alte Rechtslage, darum gerechnet ohne Option
    val atOld = rows.filter { it.oldHolding == true }
    // Gebühr-Aufwände (feeExpense, z. B. Gebühr verbraucht den ganzen Zugang) sind kein Spekulationsgeschäft: sie bleiben
    // wie bisher im Posten Neubestand, auch vor dem 1.3.2022
    val atEarlyNew = if (template == TaxTemplate.AT) {
        rows.filter { it.oldHolding != true && it.feeExpense == null && it.soldDate < TaxTemplate.AT_SWAP_NEUTRAL_FROM }
    } else emptyList()
    val atOption = atEarlyNew.any { it.soldDate >= TaxTemplate.AT_OPTION_FROM }
    // UK/CA: Käufe bis 30 Tage nach dem Ende des Steuerjahrs können die Zahlen noch ändern (UK: Bed-and-Breakfast,
    // CRYPTO22200; CA: Superficial Loss) — solange dieses Fenster offen ist (Käufe am letzten Tag zählen noch), Vermerk
    // am Posten netto. Ein solcher Kauf trifft nur Veräusserungen der letzten 30 Tage des Steuerjahrs (CA: mit Verlust,
    // in der Steuerwährung entschieden); ohne sie bleibt der Vermerk weg
    val windowDays = when (template) {
        TaxTemplate.UK -> TaxTemplate.UK_BB_DAYS
        TaxTemplate.CA -> TaxTemplate.CA_WINDOW_DAYS
        else -> null
    }
    val windowEnd = windowDays?.let { days ->
        val lateFrom = period.end.minusDays(days - 1)
        val affected = rows.any { r ->
            !r.soldDate.isBefore(lateFrom) &&
                (template != TaxTemplate.CA || ((if (fx) r.gainTarget else null) ?: r.gainUsdt)?.let { it.signum() < 0 } == true)
        }
        period.end.plusDays(days).takeIf { affected && !it.isBefore(today ?: LocalDate.now(zone)) }
    }
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
        // Neubestand zum Sondersteuersatz: Verkäufe ab dem 1.3.2022; Gebühr-Aufwände wie bisher hier (nicht in den
        // Spekulationsgeschäften, siehe [atEarlyNew])
        val newRows = rows.filter {
            it.oldHolding != true && (it.soldDate >= TaxTemplate.AT_SWAP_NEUTRAL_FROM || it.feeExpense != null)
        }
        net = Acc(newRows, all, fx)
        lines += Acc(newRows, pos, fx).line(TaxLine.GAINS, fx, false)
        lines += Acc(newRows, neg, fx).line(TaxLine.LOSSES, fx, false)
        lines += net.line(TaxLine.NEW_NET, fx)
        if (atOld.isNotEmpty() || atEarlyNew.isNotEmpty()) {
            // Spekulationsgeschäfte nach alter Rechtslage: Altbestand ≤ 1 Jahr (bzw. ohne Haltedauer) und Neuvermögen,
            // verkauft vor dem 1.3.2022; Vermerk zur Option, wenn Verkäufe vom 1.1. bis 28.2.2022 darin sind
            lines += Acc(atOld.filter { it.longTerm != true } + atEarlyNew, all, fx)
                .line(TaxLine.OLD_SHORT_NET, fx, note = TaxSummaryNote.AT_TRANSITION_2021.takeIf { atOption })
            lines += Acc(atOld.filter { it.longTerm == true }, all, fx).line(TaxLine.OLD_LONG_NET, fx)
        }
    } else {
        net = Acc(rows, all, fx)
        // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
        // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
        val info = TaxSummaryNote.CH_FIFO_INFO.takeIf { template == TaxTemplate.CH }
        lines += Acc(rows, pos, fx).line(TaxLine.GAINS, fx, false, info)
        lines += Acc(rows, neg, fx).line(TaxLine.LOSSES, fx, false, info)
        // FR: beobachtete Adressen im Gesamtwert ohne Einstand — nicht ausgeschlossen (gehören zum Gesamtwert), nur vermerkt
        val frAddress = TaxSummaryNote.FR_ADDRESS_NO_COST.takeIf {
            template == TaxTemplate.FR && rows.any { r -> r.addressValueIncluded }
        }
        lines += if (windowEnd != null) {
            net.line(TaxLine.NET, fx, note = TaxSummaryNote.WINDOW_OPEN).copy(noteDate = windowEnd)
        } else {
            net.line(TaxLine.NET, fx, note = info ?: frAddress)
        }
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
    val deniedUsdt = adjRows.fold(zero) { a, r -> a + (r.adjustmentUsdt ?: zero) }
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
    // UK: Erlöse über der Meldegrenze sind HMRC zu melden (wer für Self Assessment registriert ist), auch ohne steuerbaren
    // Gewinn — geprüft mit den Erlösen vor Gebühren (ohne Wert vor Gebühren: der Erlös), nur in GBP prüfbar
    val ukReport = if (template == TaxTemplate.UK && fx && currency.trim().uppercase() == "GBP") {
        val beforeFees = sold.fold(zero) { a, r -> a + (r.proceedsBeforeFeesTarget ?: r.proceedsTarget ?: zero) }
        TaxTemplate.ukReportProceedsGbp(startYear)?.let { beforeFees > it } ?: false
    } else false
    lines += TaxSummaryLine(
        TaxLine.PROCEEDS, procUsdt, procTarget.takeIf { fx }, incomplete = procMissing, noFx = procFx,
        note = TaxSummaryNote.UK_HMRC_REPORT.takeIf { ukReport },
    )
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
    // AT: Zinsen vor dem 1.3.2022 — alte Rechtslage (Tarif), zählen nicht zum Sondersteuersatz
    if (interestOld.isNotEmpty()) {
        lines += TaxSummaryLine(
            TaxLine.AT_INTEREST_OLD, interestOld.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) },
            interestOld.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }.takeIf { fx },
            incomplete = interestOld.any { it.valueMissing }, noFx = fx && interestOld.any { it.fxMissing },
            note = TaxSummaryNote.AT_INTEREST_OLD_LAW,
        )
    }
    // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»
    if (template == TaxTemplate.FR) {
        frOutflowLine(trades, method, priceAt, period, zone, currency, rates, usdtUsd, fx)?.let { lines += it }
    }
    val netIncomplete = net.incomplete
    val netFx = net.fxMissing > 0
    fun scaled(v: BigDecimal, f: BigDecimal): BigDecimal = v.multiply(f, PortfolioDecimal.MC)
    // CH: Vermögenswert per 31.12. — Posten mit eigenem «(Orientierung)» vor dem Orientierungs-Block; nur für ein
    // abgelaufenes Steuerjahr (der Tagesschluss des 31.12. steht erst danach fest — nie ein laufender oder heutiger Kurs).
    // Devisenkurs des 31.12. nach derselben Regel wie jede Zeile ([rateOn], höchstens 7 Tage davor, × USDT/USD)
    var wealth: TaxWealth? = null
    if (template == TaxTemplate.CH) {
        if (yearEndCloses != null && period.end.isBefore(today ?: LocalDate.now(zone))) {
            wealth = chWealth(
                trades, method.costMethod ?: CostMethod.DEFAULT, period.end, zone, yearEndCloses,
                rateOn(rates, period.end, currency, usdtUsd), yearEndAddresses,
            )
        }
        lines += chWealthLine(wealth, fx)
    }
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
            // Geschätzter Tageskurs in einer gezählten Einkunft: Vermerk an der 256-€-Grenze
            val incEstimated = income.any { it.estimated && it.valueUsdt != null }
            lines += TaxSummaryLine(TaxLine.DE_INCOME_LIMIT, null, INCOME_LIMIT_EUR, incomeStatus, estimated = incEstimated)
        }
        TaxTemplate.AT -> {
            // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
            // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG); Vermerk, wenn Belohnungen vor dem 1.3.2022 mit
            // Anschaffungskosten 0 im Neubestand stecken (Rechtslage dafür nicht ausdrücklich geklärt)
            lines += TaxSummaryLine(
                TaxLine.AT_RATE, net.usdt + intUsdt, if (fx) net.target + intTarget else null,
                incomplete = netIncomplete || intMissing, noFx = netFx || intFx,
                note = TaxSummaryNote.AT_STAKING_ZERO_COST.takeIf { atEarlyRewards(trades, zone, period) },
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
                    spec.target <= AT_SPECULATION_LIMIT_EUR -> TaxLimitStatus.BELOW
                    else -> TaxLimitStatus.REACHED
                }
                lines += TaxSummaryLine(
                    TaxLine.AT_OLD_SPECULATION_LIMIT, null, AT_SPECULATION_LIMIT_EUR, specStatus,
                    note = TaxSummaryNote.AT_WITHOUT_OPTION.takeIf { atOption },
                )
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
            // Nur in CAD (Gewinn/Verlust ist in CAD entschieden, Entscheid des Inhabers): keine USDT-Spalte. Netto ein
            // Verlust → steuerbar 0, der abziehbare Teil (× 1/2) ist als «net capital loss» vorzutragen (CRA)
            val f = TaxTemplate.CA_INCLUSION
            val taxable = if (fx) scaled(net.target + deniedTarget, f) else null
            lines += TaxSummaryLine(
                TaxLine.CA_TAXABLE, null, taxable?.max(zero), incomplete = netIncomplete, noFx = !fx || netFx || deniedFx,
            )
            if (taxable != null && taxable.signum() < 0) {
                lines += TaxSummaryLine(
                    TaxLine.NET_CAPITAL_LOSS, null, taxable.negate(), incomplete = netIncomplete, noFx = netFx || deniedFx,
                )
            }
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
            // Netto ein Verlust: steuerbar 0, der ganze Verlust ist vorzutragen (ATO «net capital loss», ohne Rabatt)
            val u = discounted { it.gainUsdt }
            val t = if (fx) discounted { it.gainTarget } else null
            lines += TaxSummaryLine(TaxLine.AU_DISCOUNTED, u.max(zero), t?.max(zero), incomplete = netIncomplete, noFx = netFx)
            if ((if (fx) t else u)?.signum() == -1) {
                lines += TaxSummaryLine(
                    TaxLine.NET_CAPITAL_LOSS, u.negate().max(zero), t?.negate()?.max(zero), incomplete = netIncomplete, noFx = netFx,
                )
            }
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
    // USDT = 1 irgendwo in der Übersicht (Tag ohne USDT/USD-Schluss, z. B. vor dem Fenster der Quellen): nie still
    val usdtApproximate = allRows.any { it.usdtApproximate } || (allInterest + income).any { it.usdtApproximate } ||
        lines.any { it.usdtApproximate } || (wealth != null && usdtApproximateOn(usdtUsd, period.end))
    return TaxYearSummary(template, method, period, currency, fx, lines, walletFrom, wealth, usdtApproximate)
}
