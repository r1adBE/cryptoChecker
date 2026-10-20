package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId

// CSV der Steuer-Exporte ([TaxExport]): UTF-8 mit BOM, CRLF, Trenn-/Dezimalzeichen je Region ([CutoffCsvFormat]).

private const val BOM = "\uFEFF"
private const val EOL = "\r\n"

/**
 * Summe der bekannten Werte; null, wenn keiner bekannt ist (und es Zeilen gibt). Summiert werden die auf 2 Stellen
 * GERUNDETEN Werte, wie sie in den Zeilen stehen ([CutoffExport.roundedMoney]) — so geht das Nachsummieren der Spalte
 * auf (0.34 + 0.34 + 0.34 = 1.02, nicht die exakte Summe 1.01). Die Jahresübersicht rechnet ihre Posten weiter exakt.
 */
private fun <T> sum(rows: List<T>, pick: (T) -> BigDecimal?): BigDecimal? {
    val known = rows.mapNotNull(pick)
    if (known.isEmpty()) return if (rows.isEmpty()) BigDecimal.ZERO else null
    return known.fold(BigDecimal.ZERO) { a, b -> a + CutoffExport.roundedMoney(b) }
}

/**
 * CSV «Realisierte Gewinne» mit BOM; [header] = Kopfzeilen (je ein Feld), danach die Zeitzone
 * ([TaxCsvTexts.timeZone]). Summe nur über vollständige Zeilen (Einstand, Erlös und Gewinn bekannt — je für
 * USDT und Anzeigewährung), so geht Erlös − Einstand = Gewinn auf; Zeilen mit unbekanntem Einstand summiert
 * die Zeile «Erlös ohne Kaufpreis». Zusatzspalten vor «Hinweis» nur, wenn eine Zeile sie hat: nicht abziehbarer
 * Verlust (CA), Abtretungspreis vor Gebühren und Gesamtwert des Portfolios (FR). Textfelder sind gegen
 * Formel-Injektion geschützt ([CutoffExport.escape]), Zahlen kommen unverändert vom Zahlenformat.
 */
fun TaxExport.gainsCsv(
    rows: List<TaxGainRow>,
    texts: TaxCsvTexts,
    header: List<String>,
    format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
    /** [lostFeeCostsIn]: je eine Zeile nach der Summe (Datum, Einstand USDT, Vermerk [TaxCsvTexts.lostFeeCost]). */
    lostFeeCosts: List<PortfolioLostFeeCost> = emptyList(),
    zone: ZoneId = ZoneId.of("UTC"),
    /** [unassignedFeesIn]: je eine Zeile nach der Summe (Datum, Betrag USDT, Vermerk [TaxCsvTexts.unassignedFee]). */
    unassignedFees: List<PortfolioUnassignedFee> = emptyList(),
): String {
    fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
    fun money(v: BigDecimal?): CsvCell = CsvCell(n(v?.let { CutoffExport.money(it) }.orEmpty()), numeric = true)
    fun t(v: String): CsvCell = CsvCell(v)
    val adj = rows.any { it.adjustmentUsdt != null }
    val fr = rows.any { it.portfolioValueUsdt != null || it.grossProceedsUsdt != null }
    /** Zusatzspalten einer Zeile (leer, wenn es sie nicht gibt). */
    fun extra(a: BigDecimal?, aT: BigDecimal?, g: BigDecimal?, gT: BigDecimal?, v: BigDecimal?, vT: BigDecimal?): List<CsvCell> =
        (if (adj) listOf(money(a), money(aT)) else emptyList()) +
            (if (fr) listOf(money(g), money(gT), money(v), money(vT)) else emptyList())
    val blank = CsvCell("", numeric = true)
    val sb = StringBuilder(BOM)
    header.forEach { sb.append(line(listOf(t(it)), format)) }
    if (texts.timeZone.isNotEmpty()) sb.append(line(listOf(t(texts.timeZone)), format))
    sb.append(
        line(
            listOf(
                texts.coin, texts.acquired, texts.sold, texts.holdingDays, texts.longTerm, texts.amount,
                texts.costUsdt, texts.proceedsUsdt, texts.gainUsdt,
                texts.costTarget, texts.proceedsTarget, texts.gainTarget,
            ).map { t(it) } +
                (if (adj) listOf(t(texts.adjustmentUsdt), t(texts.adjustmentTarget)) else emptyList()) +
                (if (fr) listOf(t(texts.grossUsdt), t(texts.grossTarget), t(texts.portfolioValueUsdt), t(texts.portfolioValueTarget)) else emptyList()) +
                t(texts.note),
            format,
        )
    )
    for (r in rows) {
        val notes = listOfNotNull(
            when (r.feeExpense) {
                PortfolioFeeExpense.CONSUMED -> texts.feeConsumed
                PortfolioFeeExpense.WITHDRAWAL -> texts.withdrawalFeeInfo.takeIf { r.feeInfoOnly && it.isNotEmpty() } ?: texts.withdrawalFee
                PortfolioFeeExpense.TRANSFER -> texts.transferFeeInfo
                null -> null
            }?.takeIf { it.isNotEmpty() },
            texts.feeRow.takeIf { r.feeRow && it.isNotEmpty() },
            texts.gift.takeIf { r.gift && it.isNotEmpty() },
            texts.spouseGift.takeIf { r.spouseGift && it.isNotEmpty() },
            texts.payment.takeIf { r.payment && it.isNotEmpty() },
            texts.valueEstimated.takeIf { r.valueEstimated && it.isNotEmpty() },
            texts.usdtAssumed.takeIf { r.usdtApproximate && it.isNotEmpty() },
            when (r.match) {
                TaxMatch.SAME_DAY -> texts.matchSameDay
                TaxMatch.THIRTY_DAY -> texts.matchThirtyDay
                TaxMatch.POOL -> texts.matchPool
                null -> null
            }?.takeIf { it.isNotEmpty() },
            when (r.oldHolding) {
                true -> texts.oldHolding
                false -> texts.newHolding
                null -> null
            }?.takeIf { it.isNotEmpty() },
            texts.swap.takeIf { r.swap && it.isNotEmpty() },
            r.wallet?.takeIf { texts.wallet.isNotEmpty() }?.let { w ->
                texts.wallet.replace("%s", w.ifEmpty { texts.walletUnspecified })
            },
            texts.feeLeg.takeIf { r.feeLeg },
            texts.costMissing.takeIf { r.costMissing },
            texts.oversold.takeIf { r.uncovered && it.isNotEmpty() },
            texts.proceedsMissing.takeIf { r.proceedsMissing },
            texts.feeValueMissing.takeIf { r.feeValueMissing },
            texts.costIncomplete.takeIf { r.costIncomplete && it.isNotEmpty() },
            texts.adjustment.takeIf { r.adjustmentUsdt != null && it.isNotEmpty() },
            texts.avgCostRates.takeIf { r.pooled && r.costTarget != null },
            texts.noFx.takeIf { r.fxMissing },
        ).joinToString(", ")
        sb.append(
            line(
                listOf(
                    t(r.coin),
                    t(r.acquiredDate?.toString().orEmpty()),
                    t(r.soldDate.toString()),
                    CsvCell(r.holdingDays?.toString().orEmpty(), numeric = true),
                    t(when (r.longTerm) { true -> texts.yes; false -> texts.no; null -> "" }),
                    CsvCell(n(CutoffExport.amount(r.amount)), numeric = true),
                    money(r.costUsdt), money(r.proceedsUsdt), money(r.gainUsdt),
                    money(r.costTarget), money(r.proceedsTarget), money(r.gainTarget),
                ) + extra(r.adjustmentUsdt, r.adjustmentTarget, r.grossProceedsUsdt, r.grossProceedsTarget, r.portfolioValueUsdt, r.portfolioValueTarget) +
                    t(notes),
                format,
            )
        )
    }
    // Summe nur vollständiger Zeilen; fehlt irgendwo etwas, «unvollständig». Gebühren nur zur Information
    // ([TaxGainRow.feeInfoOnly]) zählen nicht mit
    val counted = rows.filter { !it.feeInfoOnly }
    val incomplete = counted.any { it.costMissing || it.proceedsMissing || it.feeValueMissing || it.fxMissing || it.costIncomplete }
    val usdt = counted.filter { it.completeUsdt }
    val target = counted.filter { it.completeTarget }
    fun total(list: List<TaxGainRow>, pick: (TaxGainRow) -> BigDecimal?): BigDecimal? =
        if (list.isEmpty() && counted.isNotEmpty()) null else sum(list, pick)
    sb.append(
        line(
            listOf(
                t(texts.total), t(""), t(""), blank, t(""), blank,
                money(total(usdt) { it.costUsdt }), money(total(usdt) { it.proceedsUsdt }), money(total(usdt) { it.gainUsdt }),
                money(total(target) { it.costTarget }), money(total(target) { it.proceedsTarget }), money(total(target) { it.gainTarget }),
            ) + extra(
                sum(counted) { it.adjustmentUsdt }, sum(counted) { it.adjustmentTarget },
                sum(counted) { it.grossProceedsUsdt }, sum(counted) { it.grossProceedsTarget }, null, null,
            ) + t(if (incomplete) texts.incomplete else ""),
            format,
        )
    )
    // Erlös der Zeilen ohne Kaufpreis (nicht in der Summe oben)
    val noCost = counted.filter { it.costMissing && it.proceedsUsdt != null }
    if (noCost.isNotEmpty()) {
        sb.append(
            line(
                listOf(
                    t(texts.proceedsWithoutCost), t(""), t(""), blank, t(""), blank,
                    blank, money(sum(noCost) { it.proceedsUsdt }), blank,
                    blank, money(sum(noCost) { it.proceedsTarget }), blank,
                ) + extra(null, null, null, null, null, null) + t(texts.incomplete),
                format,
            )
        )
    }
    // Netzwerkgebühr-Einstand ohne bezahlten Posten: kein Gewinn/Verlust, aber nicht still verloren
    if (texts.lostFeeCost.isNotEmpty()) {
        for (l in lostFeeCosts) {
            sb.append(
                line(
                    listOf(
                        t(l.coin), t(""), t(localDate(l.time, zone).toString()), blank, t(""), blank,
                        money(l.costUsdt), blank, blank, blank, blank, blank,
                    ) + extra(null, null, null, null, null, null) + t(texts.lostFeeCost),
                    format,
                )
            )
        }
    }
    // Umbuchungsgebühr auf Bestand ohne Kaufpreis: offener Posten, sichtbar statt still verworfen
    if (texts.unassignedFee.isNotEmpty()) {
        for (f in unassignedFees) {
            sb.append(
                line(
                    listOf(
                        t(f.coin), t(""), t(localDate(f.time, zone).toString()), blank, t(""), blank,
                        money(f.usdt), blank, blank, blank, blank, blank,
                    ) + extra(null, null, null, null, null, null) + t(texts.unassignedFee),
                    format,
                )
            )
        }
    }
    return sb.toString()
}

/**
 * CSV «Einkünfte» mit BOM; [header] = Kopfzeilen (je ein Feld). Mit [TaxCsvTexts.totalInterest] und
 * [TaxCsvTexts.totalStaking] (AT) Zins und übrige Belohnungen getrennt: Vermerk je Zeile und je eine Summenzeile mit
 * eigenem «unvollständig» — Zinsen sind beim Zufluss steuerpflichtig, Staking im Neubestand nur Information (sonst
 * doppelt mit dem Verkaufsgewinn bei Anschaffungskosten 0; gleiche Einteilung wie [yearSummary]).
 */
fun TaxExport.incomeCsv(
    rows: List<TaxIncomeRow>,
    texts: TaxCsvTexts,
    header: List<String>,
    format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
): String {
    fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
    fun money(v: BigDecimal?): CsvCell = CsvCell(n(v?.let { CutoffExport.money(it) }.orEmpty()), numeric = true)
    fun t(v: String): CsvCell = CsvCell(v)
    val sb = StringBuilder(BOM)
    header.forEach { sb.append(line(listOf(t(it)), format)) }
    if (texts.timeZone.isNotEmpty()) sb.append(line(listOf(t(texts.timeZone)), format))
    sb.append(line(listOf(texts.date, texts.coin, texts.amount, texts.valueUsdt, texts.valueTarget, texts.note).map { t(it) }, format))
    val split = texts.totalInterest.isNotEmpty() && texts.totalStaking.isNotEmpty()
    for (r in rows) {
        // Zufluss vor dem 1.3.2022: alte Rechtslage — eigener Vermerk statt «steuerpflichtig»/«nur Information»
        val early = texts.incomeBeforeNewLaw.isNotEmpty() && r.date < TaxTemplate.AT_SWAP_NEUTRAL_FROM
        val kind = when {
            early -> texts.incomeBeforeNewLaw
            r.interest -> texts.incomeInterest
            else -> texts.incomeStaking
        }
        val notes = listOfNotNull(
            kind.takeIf { split && it.isNotEmpty() },
            texts.valueMissing.takeIf { r.valueMissing },
            texts.valueEstimated.takeIf { r.estimated && it.isNotEmpty() },
            texts.usdtAssumed.takeIf { r.usdtApproximate && it.isNotEmpty() },
            texts.noFx.takeIf { r.fxMissing },
        ).joinToString(", ")
        sb.append(
            line(
                listOf(
                    t(r.date.toString()), t(r.coin), CsvCell(n(CutoffExport.amount(r.amount)), numeric = true),
                    money(r.valueUsdt), money(r.valueTarget), t(notes),
                ),
                format,
            )
        )
    }
    fun total(label: String, list: List<TaxIncomeRow>) {
        val incomplete = list.any { it.valueMissing || it.fxMissing }
        sb.append(
            line(
                listOf(
                    t(label), t(""), CsvCell("", numeric = true), money(sum(list) { it.valueUsdt }), money(sum(list) { it.valueTarget }),
                    t(if (incomplete) texts.incomplete else ""),
                ),
                format,
            )
        )
    }
    if (split) {
        val (interest, staking) = rows.partition { it.interest }
        total(texts.totalInterest, interest)
        total(texts.totalStaking, staking)
    } else {
        total(texts.total, rows)
    }
    return sb.toString()
}

/**
 * CSV «Jahresübersicht» mit BOM: [header] (je ein Feld: Titel, Vorlage/Methode/Steuerjahr/Währung, Arbeitsunterlage),
 * Hinweis «Orientierung – keine Steuerberatung», Zeitzone ([zoneLine]), dann Posten | USDT | Währung | Hinweis — zuerst
 * nur Tatsachen (Summen). Schlüsse nach den Regeln der Vorlage ([TaxLine.orientation]; Sätze, Grenzen, Freibeträge)
 * stehen NUR im eigenen Block danach: Kopf [TaxSummaryTexts.orientationHeading], jeder Posten mit Vorsatz
 * «Orientierung:» ([taxOrientationLabel]). CH: unter dem Posten Vermögenswert per 31.12. ([TaxLine.CH_WEALTH_VALUE]) je
 * Coin eine Zeile ([TaxYearSummary.wealth], Bezeichnung [wealthCoinLabel], Hinweis [wealthCoinNote]).
 */
fun TaxExport.summaryCsv(
    s: TaxYearSummary,
    texts: TaxSummaryTexts,
    header: List<String>,
    zoneLine: String = "",
    format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
    /** Zählende Adresse mit Eröffnungsposten ([AddressCostLots.hasCountedLots]): mit CH-Vermögen Zeile [TaxSummaryTexts.addressLots]. */
    addressLots: Boolean = false,
): String {
    fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
    fun money(v: BigDecimal?): String = n(v?.let { CutoffExport.money(it) }.orEmpty())
    fun row(l: TaxSummaryLine, label: String): String {
        val notes = listOfNotNull(
            when (l.status) {
                TaxLimitStatus.BELOW -> texts.below
                TaxLimitStatus.REACHED -> texts.reached
                TaxLimitStatus.UNKNOWN -> texts.unknown
                TaxLimitStatus.LOSS -> texts.loss.ifEmpty { texts.below }
                null -> null
            },
            texts.incomplete.takeIf { l.incomplete },
            texts.noFx.takeIf { l.noFx },
            texts.notCalculated.takeIf { l.notCalculated && it.isNotEmpty() },
            l.noteText(texts),
        ).joinToString(", ")
        return textLine(listOf(label, money(l.usdt), money(l.target), notes), format, setOf(1, 2))
    }
    val sb = StringBuilder(BOM)
    header.forEach { sb.append(textLine(listOf(it), format)) }
    sb.append(textLine(listOf(texts.title), format))
    sb.append(textLine(listOf(texts.disclaimer), format))
    if (zoneLine.isNotEmpty()) sb.append(textLine(listOf(zoneLine), format))
    // USDT = 1 an einem nötigen Tag: eigene Zeile (die Posten der Übersicht tragen keinen Vermerk je Transaktion)
    if (s.usdtApproximate && texts.usdtAssumed.isNotEmpty()) sb.append(textLine(listOf(texts.usdtAssumed), format))
    // CH-Vermögen mit Adressen mit Einstand: Umbuchungen zur/von der Adresse evtl. doppelt/fehlend (keine Rechenänderung)
    if (addressLots && s.wealth != null && texts.addressLots.isNotEmpty()) sb.append(textLine(listOf(texts.addressLots), format))
    sb.append(textLine(listOf(texts.item, texts.usdt, texts.target, texts.note), format))
    val (orientation, facts) = s.lines.partition { it.line.orientation }
    for (l in facts) {
        sb.append(row(l, texts.labels[l.line].orEmpty()))
        // CH Vermögenswert: darunter je Coin eine Zeile in denselben Spalten (Coin – Bestand | USDT | Währung | Hinweis);
        // Werte je Coin gerundet im Text, die Summe des Postens aus genau diesen gerundeten Werten ([chWealth])
        if (l.line == TaxLine.CH_WEALTH_VALUE) {
            s.wealth?.rows?.forEach { r ->
                val label = texts.wealthCoinLabel(r.coin, n(CutoffExport.amount(r.amount)))
                sb.append(
                    textLine(
                        listOf(label, money(r.valueUsdt), money(r.valueTarget.takeIf { s.fxAvailable }), texts.wealthCoinNote(r)),
                        format, setOf(1, 2),
                    )
                )
            }
        }
    }
    if (orientation.isNotEmpty()) {
        sb.append(textLine(listOf(texts.orientationHeading.ifEmpty { texts.orientation }), format))
        for (l in orientation) {
            sb.append(row(l, taxOrientationLabel(texts.orientationItem, texts.orientation, texts.labels[l.line].orEmpty())))
        }
    }
    return sb.toString()
}

/** Zeile aus typisierten Zellen (Text gegen Formel-Injektion geschützt, Zahlen unverändert). */
private fun line(cells: List<CsvCell>, format: CutoffCsvFormat): String = CutoffExport.joinCells(cells, format.separator) + EOL

/** Zeile [texts] (Text) und [numbers] (Zahlen, z. B. Beträge der Übersicht) in dieser Reihenfolge der Spalten. */
private fun textLine(fields: List<String>, format: CutoffCsvFormat, numeric: Set<Int> = emptySet()): String =
    line(fields.mapIndexed { i, f -> CsvCell(f, numeric = i in numeric) }, format)
