package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// CH: Vermögenswert per 31.12. als Orientierung ([TaxExport.chWealth]) — Spiegel: `TaxWealthCh.swift`.

/**
 * Ein Coin im Vermögenswert per 31.12. ([TaxWealth]): eigener Bestand und Guthaben beobachteter Adressen zusammen (das
 * Vermögen zählt je Person, nicht je Wallet). Zahlen exakt, gerundet wird erst im Text.
 */
data class TaxWealthRow(
    val coin: String,
    val amount: BigDecimal,
    /** Tagesschluss 31.12. in USDT; null = keiner. */
    val priceUsdt: BigDecimal?,
    /** [amount] × [priceUsdt]; null = nicht gerechnet. */
    val valueUsdt: BigDecimal?,
    /** [valueUsdt] × Devisenkurs; null = nicht gerechnet bzw. ohne Devisenkurs. */
    val valueTarget: BigDecimal?,
    /** Kein Tagesschluss 31.12. ([CutoffRow.noPrice]). */
    val noPrice: Boolean = false,
    /** Kurs nicht als derselbe Token bestätigt ([CutoffRow.unchecked]). */
    val unchecked: Boolean = false,
    /** Eine Adresse war am 31.12. nicht beobachtet ([CutoffRow.addressUnobserved]) — ihr Guthaben fehlt. */
    val addressUnobserved: Boolean = false,
    /** Kein Devisenkurs für den 31.12. */
    val noFx: Boolean = false,
) {
    /** Wert des Coins nicht gerechnet (Kurs fehlt, ungeprüft oder Guthaben unbekannt) — nie ein anderer Kurs. */
    val missing: Boolean get() = valueUsdt == null
}

/**
 * CH: Vermögenswert per [date] (31.12. des Steuerjahrs) — ORIENTIERUNG: Bestand × Börsen-Tagesschluss × Devisenkurs,
 * nicht die Steuerwerte der ESTV-Kursliste; die Vermögenssteuer selbst rechnet die App nicht (kantonale Tarife).
 * Werte je Coin exakt (gerundet auf 2 Stellen erst in Anzeige bzw. CSV, [CutoffExport.money]); die Summen aber aus den
 * GERUNDETEN Werten je Coin ([CutoffExport.roundedMoney]), wie sie in den Zeilen darunter stehen — wie der Stichtag-Export
 * ([CutoffExport.csv]): wer nachrechnet, kommt auf das Total (3 × 0.335 → 1.02, nicht 1.01).
 */
data class TaxWealth(
    val date: LocalDate,
    val rows: List<TaxWealthRow>,
    /** Summe der bekannten, je Coin auf 2 Stellen gerundeten Werte in USDT. */
    val totalUsdt: BigDecimal,
    /** Summe der bekannten, je Coin gerundeten Werte in der Steuerwährung; null = ohne Devisenkurs. */
    val totalTarget: BigDecimal?,
) {
    /** Mindestens ein Coin nicht gerechnet — die Summe ist «unvollständig». */
    val incomplete: Boolean get() = rows.any { it.missing }

    /** Kein Devisenkurs für den Stichtag (bei Bestand). */
    val noFx: Boolean get() = totalTarget == null && rows.isNotEmpty()
}

/**
 * CH: Vermögenswert per 31.12. ([date]) aus den Transaktionen — dieselbe Rechnung wie der Stichtag-Export der
 * Arbeitsunterlage ([CutoffExport]), damit beide Dateien dieselben Werte zeigen:
 *  - Bestand am Ende des Tags in der Zeitzone der Vorlage ([CutoffExport.endOfDayMillis]: 31.12. 23:59:59.999 Zürich zählt,
 *    1.1. 00:00 nicht) nach der Einstandsmethode [method] ([CutoffExport.holdingsAt]: Gebühren im Coin mindern den
 *    Bestand, Umbuchungen zwischen eigenen Wallets ändern die Summe nicht); dazu beobachtete Adressen ([addresses],
 *    [CutoffExport.addressRows]), je Coin zusammengezählt.
 *  - Kurs = Tagesschluss (UTC-Tageskerze) des 31.12. aus [closes] ([CutoffCloses], gleiche Quelle und Identitätsprüfung wie
 *    der Stichtag-Export); Stablecoins nach [PortfolioStables] (USDT = 1, die übrigen ihr Tagesschluss).
 *  - [rate] = USD → Steuerwährung samt USDT/USD des Tags ([TaxExport.rateOn] am 31.12.: letzter Kurs höchstens
 *    [TaxExport.FX_MAX_GAP_DAYS] Tage davor, wie jede Zeile der Arbeitsunterlage); null = ohne Devisenkurs.
 * Fehlt der Schluss eines Coins, ist er ungeprüft oder fehlt ein Teil seines Guthabens, bleibt SEIN Wert leer («nicht
 * gerechnet», nie der heutige Kurs) und die Summe ist unvollständig. Alphabetisch nach Coin.
 */
fun TaxExport.chWealth(
    trades: List<PortfolioTrade>,
    method: CostMethod,
    date: LocalDate,
    zone: ZoneId,
    closes: CutoffCloses,
    rate: BigDecimal?,
    addresses: AddressBalancesAt? = null,
): TaxWealth {
    val cutoff = CutoffExport.endOfDayMillis(date, zone)
    val holdings = CutoffExport.holdingsAt(trades.filter { it.amount.signum() > 0 }, cutoff, method)
    val parts = CutoffExport.rows(holdings, closes.prices, rate, closes.unchecked) +
        (addresses?.let { CutoffExport.addressRows(it, closes.prices, rate, closes.unchecked) } ?: emptyList())
    val rows = parts.groupBy { it.coin }.toSortedMap().map { (coin, list) ->
        val noPrice = list.any { it.noPrice }
        val unchecked = list.any { it.unchecked }
        val unobserved = list.any { it.addressUnobserved }
        // Ein Teil ohne Wert (oder ein unbeobachtetes Guthaben) → der ganze Coin ist nicht gerechnet
        val missing = noPrice || unchecked || unobserved || list.any { it.valueUsdt == null }
        val valueUsdt = if (missing) null else list.fold(BigDecimal.ZERO) { a, r -> a + r.valueUsdt!! }
        val valueTarget = if (missing || list.any { it.valueTarget == null }) null else {
            list.fold(BigDecimal.ZERO) { a, r -> a + r.valueTarget!! }
        }
        TaxWealthRow(
            coin = coin,
            amount = list.fold(BigDecimal.ZERO) { a, r -> a + r.amount },
            priceUsdt = list.firstNotNullOfOrNull { it.priceUsdt },
            valueUsdt = valueUsdt,
            valueTarget = valueTarget,
            noPrice = noPrice,
            unchecked = unchecked,
            addressUnobserved = unobserved,
            noFx = list.any { it.noFx },
        )
    }
    // Summen aus den gerundeten Zeilenwerten (wie sie in der Datei stehen), wie CutoffExport.csv
    val totalUsdt = rows.fold(BigDecimal.ZERO) { a, r -> r.valueUsdt?.let { a + CutoffExport.roundedMoney(it) } ?: a }
    val fx = rate?.takeIf { it.signum() > 0 }
    val totalTarget = if (fx == null && rows.isNotEmpty()) null else {
        rows.fold(BigDecimal.ZERO) { a, r -> r.valueTarget?.let { a + CutoffExport.roundedMoney(it) } ?: a }
    }
    return TaxWealth(date, rows, totalUsdt, totalTarget)
}

/**
 * Posten «Vermögenswert 31.12.<Jahr> (Orientierung)» ([TaxLine.CH_WEALTH_VALUE]) der CH-Übersicht: Summe in USDT und in
 * der Steuerwährung, «unvollständig» / «kein Devisenkurs» wie jeder Posten; ohne [wealth] (Jahr noch nicht zu Ende oder
 * Kurse nicht geladen) «nicht gerechnet». Vermerk immer [TaxSummaryNote.CH_WEALTH_ORIENTATION] (ESTV-Kursliste massgebend,
 * Vermögenssteuer nicht gerechnet).
 */
internal fun chWealthLine(wealth: TaxWealth?, fx: Boolean): TaxSummaryLine =
    if (wealth == null) {
        TaxSummaryLine(TaxLine.CH_WEALTH_VALUE, notCalculated = true, note = TaxSummaryNote.CH_WEALTH_ORIENTATION)
    } else {
        TaxSummaryLine(
            TaxLine.CH_WEALTH_VALUE, wealth.totalUsdt, wealth.totalTarget.takeIf { fx },
            incomplete = wealth.incomplete, noFx = fx && wealth.noFx, note = TaxSummaryNote.CH_WEALTH_ORIENTATION,
        )
    }
