package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Zeilen der Steuer-Exporte: realisierte Gewinne und Einkünfte je Steuerjahr ([TaxExport]).

/**
 * Eine Zeile «Realisierte Gewinne»: eine Veräusserung ([PortfolioDisposal]; FIFO je Posten, Ø je Verkauf).
 * Beträge exakt, gerundet wird erst im Text. null = unbekannt (Feld leer).
 */
data class TaxGainRow(
    val coin: String,
    /** Datum des Zugangs (Ortszeit); Ø: null. */
    val acquiredDate: LocalDate?,
    val soldDate: LocalDate,
    /** Kalendertage zwischen [acquiredDate] und [soldDate]; Ø: null. */
    val holdingDays: Long?,
    /** Länger als ein Jahr gehalten ([TaxExport.isLongTerm]); Ø: null. */
    val longTerm: Boolean?,
    val amount: BigDecimal,
    val costUsdt: BigDecimal?,
    val proceedsUsdt: BigDecimal?,
    val gainUsdt: BigDecimal?,
    /**
     * Einstand in der Anzeigewährung: Summe der Herkunftsteile ([PortfolioDisposal.costParts]) je zum Kurs ihres Datums
     * — FIFO: Erwerb und zugeschlagene Umbuchungsgebühren je zu ihrem Datum, Ø: laufender Ø in der Anzeigewährung; ohne
     * Teile Einstand × Kurs am Kaufdatum. null, wenn ein nötiger Kurs fehlt.
     */
    val costTarget: BigDecimal?,
    /** Erlös × Devisenkurs am Verkaufsdatum. */
    val proceedsTarget: BigDecimal?,
    /** [proceedsTarget] − [costTarget]. */
    val gainTarget: BigDecimal?,
    /** Ein benötigter Devisenkurs fehlt. */
    val fxMissing: Boolean,
    val feeLeg: Boolean,
    val feeValueMissing: Boolean,
    val method: CostMethod,
    /** Mehr verkauft als erfasst: Teil ohne erfassten Zugang ([PortfolioDisposal.uncovered]). */
    val uncovered: Boolean = false,
    /** Veräusserung durch einen Tausch ([PortfolioDisposal.swap]). */
    val swap: Boolean = false,
    /** [CostMethod.FIFO_WALLET]: Wallet ([PortfolioDisposal.wallet]); sonst null. */
    val wallet: String? = null,
    // ---- Steuer-Vorlagen ([TaxEngines]); Tatsachen, keine Schlüsse ----
    /** UK: Zuordnung ([PortfolioDisposal.match]). */
    val match: TaxMatch? = null,
    /** CA: nicht abziehbarer Verlust (USDT, ≥ 0). */
    val adjustmentUsdt: BigDecimal? = null,
    /** [adjustmentUsdt] × Devisenkurs am Verkaufsdatum. */
    val adjustmentTarget: BigDecimal? = null,
    /** AT: Altbestand (true) / Neubestand (false). */
    val oldHolding: Boolean? = null,
    /** FR: Erwerbspreis-Summe unvollständig. */
    val costIncomplete: Boolean = false,
    /** FR: Gesamtwert des Portfolios vor der Abtretung (USDT / Anzeigewährung am Verkaufsdatum). */
    val portfolioValueUsdt: BigDecimal? = null,
    val portfolioValueTarget: BigDecimal? = null,
    /** FR: Abtretungspreis vor Gebühren (USDT / Anzeigewährung). */
    val grossProceedsUsdt: BigDecimal? = null,
    val grossProceedsTarget: BigDecimal? = null,
    /** FR: Gesamtwert samt Guthaben beobachteter Adressen ([PortfolioDisposal.addressValueIncluded]). */
    val addressValueIncluded: Boolean = false,
    /** Einstand aus einem Durchschnitt (Herkunftsteile je Kaufdatum umgerechnet). */
    val pooled: Boolean = method == CostMethod.AVERAGE,
    /** Gebühr als Aufwand ([PortfolioDisposal.feeExpense]): Einstand = Aufwand, Erlös 0; sonst null. */
    val feeExpense: PortfolioFeeExpense? = null,
    /** Gebühr als Veräusserung ohne Erlös ([PortfolioLedger.isFeeDisposal], CSV-Zeile «Fee»): Vermerk «Gebühr». */
    val feeRow: Boolean = false,
    /** Schenkung als Veräusserung ([PortfolioDisposal.gift], UK/CA/AU): Vermerk «Schenkung». */
    val gift: Boolean = false,
    /** Zahlung mit Krypto ([PortfolioDisposal.payment]): Vermerk «Zahlung mit Krypto». */
    val payment: Boolean = false,
    /** Wert geschätzt ([PortfolioDisposal.valueEstimated], Tagesschluss): Vermerk, zählt nicht als «unvollständig». */
    val valueEstimated: Boolean = false,
    /** UK/CA: Schenkung an Ehe-/Lebenspartner ([PortfolioDisposal.spouseGift]): Erlös = Einstand, Vermerk. */
    val spouseGift: Boolean = false,
    /**
     * Gebühr in USDT/Fiat einer Auszahlung bzw. Umbuchung mit [TaxEventRules.quoteFeeIsInfo] (UK, US, CA, AU, JP), dazu die
     * Auszahlungsgebühr bei [TaxMethod.FR_PTA]: kein Verlust, nur Information — nicht in den Summen, Vermerk
     * [TaxCsvTexts.withdrawalFeeInfo] bzw. [TaxCsvTexts.transferFeeInfo].
     */
    val feeInfoOnly: Boolean = false,
    /**
     * Mit USDT/USD-Tagesschluss umgerechnet ([TaxExport.rateOn] `usdtUsd`), aber an einem nötigen Tag ohne Schluss: dort
     * USDT = 1 USD als Näherung — Vermerk [TaxCsvTexts.usdtAssumed], nie still.
     */
    val usdtApproximate: Boolean = false,
    /** UK: Erlös vor Gebühren ([PortfolioDisposal.proceedsBeforeFeesUsdt]) / × Devisenkurs am Verkaufsdatum; sonst null. */
    val proceedsBeforeFeesUsdt: BigDecimal? = null,
    val proceedsBeforeFeesTarget: BigDecimal? = null,
) {
    val costMissing: Boolean get() = costUsdt == null
    val proceedsMissing: Boolean get() = proceedsUsdt == null

    /** Einstand, Erlös und Gewinn in USDT bekannt (zählt in der Summe). */
    val completeUsdt: Boolean get() = costUsdt != null && proceedsUsdt != null && gainUsdt != null

    /** Einstand, Erlös und Gewinn in der Anzeigewährung bekannt (zählt in der Summe). */
    val completeTarget: Boolean get() = costTarget != null && proceedsTarget != null && gainTarget != null
}

/** Eine Zeile «Einkünfte»: Belohnung ([PortfolioTxType.REWARD]) mit Wert beim Erhalt. */
data class TaxIncomeRow(
    val date: LocalDate,
    val coin: String,
    val amount: BigDecimal,
    /** Menge × Wert je Coin beim Erhalt; null = Wert fehlt. */
    val valueUsdt: BigDecimal?,
    /** [valueUsdt] × Devisenkurs des Tags. */
    val valueTarget: BigDecimal?,
    val fxMissing: Boolean,
    /** Zins (Lending, [PortfolioTrade.interestIncome]) — AT: beim Zufluss Einkunft zum Sondersteuersatz ([TaxLine.AT_INTEREST]). */
    val interest: Boolean = false,
    /** Wert ohne Preis in der Datei zum Tagesschluss geschätzt ([PortfolioLedger.valueRewardsAtDayClose]): Vermerk. */
    val estimated: Boolean = false,
    /** Umrechnung mit USDT = 1 USD als Näherung (kein USDT/USD-Tagesschluss, [TaxGainRow.usdtApproximate]): Vermerk. */
    val usdtApproximate: Boolean = false,
) {
    val valueMissing: Boolean get() = valueUsdt == null
}

fun TaxExport.gainRows(
    trades: List<PortfolioTrade>,
    method: CostMethod,
    year: Int,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
): List<TaxGainRow> = gainRows(trades, TaxMethod.of(method), calendar(year), zone, currency, rates)

/**
 * Zeilen «Realisierte Veräusserungen» des Steuerjahrs [period] nach [method]. Einstand in der Anzeigewährung: mit
 * Herkunftsteilen ([PortfolioDisposal.costParts]) jeder Teil zum Kurs seines Datums, sonst zum Kurs des Kaufdatums;
 * Erlös, nicht abziehbarer Verlust, Gesamtwert und Abtretungspreis zum Kurs des Verkaufsdatums.
 */
fun TaxExport.gainRows(
    trades: List<PortfolioTrade>,
    method: TaxMethod,
    period: TaxPeriod,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
    priceAt: ((String, Long) -> BigDecimal?)? = null,
    /** Verkäufe, die Gebühren sind ([PortfolioLedger.isFeeDisposal]) — einmal gesammelt. */
    feeSales: Set<Long> = trades.filter(PortfolioLedger::isFeeDisposal).mapTo(HashSet()) { it.id },
    /**
     * Transaktionen ohne Verkauf (Umbuchung, Auszahlung, Tausch): ihre Veräusserungen ohne Tausch-Vermerk sind
     * Gebühren in Krypto (Netzwerkgebühr nach [TaxEventRules.networkFeeIsDisposal], FR Gebühr-Abtretung) — Vermerk «Gebühr».
     */
    nonSales: Set<Long> = trades.filter {
        it.type == PortfolioTxType.TRANSFER || it.type.isWithdrawalLike || it.type == PortfolioTxType.SWAP
    }.mapTo(HashSet()) { it.id },
    /** Schenkung und Netzwerkgebühr nach der Vorlage ([TaxTemplate.eventRules]); Standard nach der Rechnung. */
    rules: TaxEventRules = TaxEventRules.of(method),
    /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
    walletFrom: LocalDate? = null,
    /** USDT/USD-Tagesschluss je Datum ([rateOn]); null = USDT gilt 1 USD. */
    usdtUsd: Map<Long, BigDecimal>? = null,
): List<TaxGainRow> = disposalsIn(
    trades, method, period, zone, priceAt,
    // CA: Superficial Loss in der Steuerwährung entscheiden (dieselben Kurse wie die Zeilen)
    fx = { t -> rateOn(rates, localDate(t, zone), currency, usdtUsd) },
    rules = rules,
    walletFrom = walletFrom,
).map { d ->
    val sold = localDate(d.sellTime, zone)
    val acquired = d.acquiredTime?.let { localDate(it, zone) }
    val saleRate = rateOn(rates, sold, currency, usdtUsd)
    fun atSale(v: BigDecimal?): BigDecimal? = v?.let { x -> saleRate?.let { x.multiply(it, PortfolioDecimal.MC) } }
    // Herkunftsteile je zum Kurs ihres Datums (fehlt einer: leer); FIFO-Posten zum Kurs des Kaufdatums
    var costFxMissing = false
    val costTarget: BigDecimal? = d.costUsdt?.let { c ->
        when {
            d.costParts.isNotEmpty() -> {
                var sum: BigDecimal? = BigDecimal.ZERO
                for (part in d.costParts) {
                    val r = rateOn(rates, localDate(part.time, zone), currency, usdtUsd)
                    sum = if (r == null || sum == null) null else sum + part.usdt.multiply(r, PortfolioDecimal.MC)
                }
                sum
            }
            acquired != null -> rateOn(rates, acquired, currency, usdtUsd)?.let { c.multiply(it, PortfolioDecimal.MC) }
            // Einstand 0 (z. B. Belohnung zum Wert 0): nichts umzurechnen
            c.signum() == 0 -> BigDecimal.ZERO
            else -> saleRate?.let { c.multiply(it, PortfolioDecimal.MC) }
        }.also { if (it == null) costFxMissing = true }
    }
    // Schenkung an Ehe-/Lebenspartner: kein Gewinn/Verlust auch in der Steuerwährung (TCGA 1992 s. 58, ITA 73(1)) —
    // der Erlös gilt als genau der umgerechnete Einstand, nicht als Einstand in USDT zum Kurs des Schenkungstags
    val proceedsTarget = if (d.spouseGift) costTarget else atSale(d.proceedsUsdt)
    TaxGainRow(
        coin = d.coin,
        acquiredDate = acquired ?: d.matchedTime?.let { localDate(it, zone) },
        soldDate = sold,
        // Dieselbe Rechnung wie PortfolioFifo (dort UTC), hier in der Zeitzone der Liste
        holdingDays = d.acquiredTime?.let { PortfolioFifo.holdingDays(it, d.sellTime, zone) },
        longTerm = acquired?.let { isLongTerm(it, sold) },
        amount = d.amount,
        costUsdt = d.costUsdt,
        proceedsUsdt = d.proceedsUsdt,
        gainUsdt = d.gainUsdt,
        costTarget = costTarget,
        proceedsTarget = proceedsTarget,
        gainTarget = if (costTarget != null && proceedsTarget != null) proceedsTarget - costTarget else null,
        fxMissing = costFxMissing || (!d.spouseGift && d.proceedsUsdt != null && saleRate == null),
        feeLeg = d.feeLeg,
        feeValueMissing = d.feeValueMissing,
        method = d.method,
        uncovered = d.uncovered,
        swap = d.swap,
        wallet = d.wallet,
        match = d.match,
        adjustmentUsdt = d.adjustmentUsdt,
        adjustmentTarget = atSale(d.adjustmentUsdt),
        oldHolding = d.oldHolding,
        costIncomplete = d.costIncomplete,
        portfolioValueUsdt = d.portfolioValueUsdt,
        portfolioValueTarget = atSale(d.portfolioValueUsdt),
        grossProceedsUsdt = d.grossProceedsUsdt,
        grossProceedsTarget = atSale(d.grossProceedsUsdt),
        addressValueIncluded = d.addressValueIncluded,
        // Durchschnitt/Pool (Herkunftsteile ohne Posten-Datum); FIFO-Posten tragen ihre Teile ohne diesen Vermerk
        pooled = (d.method == CostMethod.AVERAGE && d.feeExpense == null) || (d.costParts.isNotEmpty() && d.acquiredTime == null),
        feeExpense = d.feeExpense,
        // Schenkung als Veräusserung ist keine Gebühr, auch wenn die Transaktion kein Verkauf ist
        feeRow = !d.feeLeg && d.feeExpense == null && !d.gift && !d.payment &&
            (d.sellTxId in feeSales || (!d.swap && d.sellTxId in nonSales)),
        gift = d.gift,
        payment = d.payment,
        valueEstimated = d.valueEstimated,
        spouseGift = d.spouseGift,
        // TRANSFER-Zeilen gibt es nur mit dieser Regel ([TaxEngines.disposals]). FR: die Auszahlungsgebühr mindert den
        // Gewinn nicht (art. 150 VH bis kennt nur Gebühren der Abtretung) — wie in der Jahresübersicht nur Information,
        // sonst stünde sie in der Summe der Gewinne-CSV
        feeInfoOnly = d.feeExpense == PortfolioFeeExpense.TRANSFER ||
            ((rules.quoteFeeIsInfo || method == TaxMethod.FR_PTA) && d.feeExpense == PortfolioFeeExpense.WITHDRAWAL),
        // USDT = 1 als Näherung an einem der Tage, deren Kurs die Zeile braucht (Verkauf, Kauf, Herkunftsteile)
        usdtApproximate = (listOf(sold) + listOfNotNull(acquired) + d.costParts.map { localDate(it.time, zone) })
            .any { usdtApproximateOn(usdtUsd, it) },
        proceedsBeforeFeesUsdt = d.proceedsBeforeFeesUsdt,
        proceedsBeforeFeesTarget = atSale(d.proceedsBeforeFeesUsdt),
    )
}

fun TaxExport.incomeRows(
    trades: List<PortfolioTrade>,
    year: Int,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
): List<TaxIncomeRow> = incomeRows(trades, calendar(year), zone, currency, rates)

/**
 * Belohnungen des Steuerjahrs [period] mit Wert beim Erhalt (Menge × Wert je Coin), zum Kurs des Tags. Ohne Wert in der
 * Datei mit [priceAt] zum Tagesschluss geschätzt ([PortfolioLedger.valueRewardsAtDayClose], [TaxIncomeRow.estimated]);
 * ohne Kurs bleibt der Wert leer («unvollständig»).
 */
fun TaxExport.incomeRows(
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
    priceAt: ((String, Long) -> BigDecimal?)? = null,
    /** USDT/USD-Tagesschluss je Datum ([rateOn]); null = USDT gilt 1 USD. */
    usdtUsd: Map<Long, BigDecimal>? = null,
): List<TaxIncomeRow> = PortfolioLedger.valueRewardsAtDayClose(rewardsIn(trades, period, zone), priceAt).map { t ->
    val date = localDate(t.time, zone)
    val value = t.validPrice?.let { t.amount * it }
    val rate = rateOn(rates, date, currency, usdtUsd)
    TaxIncomeRow(
        date = date,
        coin = PortfolioCalculator.normalizeCoin(t.coin),
        amount = t.amount,
        valueUsdt = value,
        valueTarget = value?.let { v -> rate?.let { v.multiply(it, PortfolioDecimal.MC) } },
        fxMissing = value != null && rate == null,
        interest = t.interestIncome,
        estimated = t.valueEstimated,
        usdtApproximate = value != null && usdtApproximateOn(usdtUsd, date),
    )
}
