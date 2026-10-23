package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Summenzeilen der CSV-Exporte aus den GERUNDETEN Zeilenwerten: drei Zeilen zu 0.335 stehen als 0.34 in der
 * Datei, das Total ist 1.02 (Nachsummieren geht auf), nicht die exakte Summe 1.005 → 1.01. iOS: `TaxCsvRoundingTests`.
 */
class TaxCsvRoundingTest {

    private val third = BigDecimal("0.335")

    @Test
    fun cutoffTotalSumsRoundedRows() {
        val texts = CutoffCsvTexts(
            coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
            rate = "Kurs", valueTarget = "Wert CHF", note = "Hinweis", total = "Total",
            noPrice = "kein Kurs", noFx = "kein Devisenkurs", unchecked = "ungeprüft", incomplete = "unvollständig",
            commentFormat = "# %1\$s %2\$s %3\$s",
        )
        val holdings = listOf("AAA", "BBB", "CCC").map { CutoffHolding(it, BigDecimal.ONE) }
        val rows = CutoffExport.rows(holdings, holdings.associate { it.coin to third }, BigDecimal.ONE)
        val lines = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", "2025-12-31", rows, texts)
            .removePrefix("﻿").split("\r\n")
        assertEquals("AAA;1;0.335;0.34;1;0.34;", lines[2])
        assertEquals("Total;;;1.02;1;1.02;", lines[5])
    }

    @Test
    fun incomeTotalSumsRoundedRows() {
        val texts = TaxCsvTexts(
            coin = "Coin", acquired = "Acq", sold = "Sold", holdingDays = "Days", longTerm = "> 1y", amount = "Amount",
            costUsdt = "Cost", proceedsUsdt = "Proceeds", gainUsdt = "Gain", costTarget = "CostT", proceedsTarget = "ProcT",
            gainTarget = "GainT", date = "Date", valueUsdt = "V", valueTarget = "VT", note = "Note", total = "Total",
            yes = "yes", no = "no", costMissing = "cm", proceedsMissing = "pm", feeLeg = "fl", feeValueMissing = "fv",
            avgCostRates = "avg", valueMissing = "vm", noFx = "nofx", incomplete = "inc", wallet = "%s",
        )
        val rows = (1..3).map {
            TaxIncomeRow(LocalDate.of(2025, 3, it), "SOL", BigDecimal.ONE, third, third, fxMissing = false)
        }
        val lines = TaxExport.incomeCsv(rows, texts, emptyList()).removePrefix("﻿").split("\r\n")
        assertEquals("2025-03-01;SOL;1;0.34;0.34;", lines[1])
        assertEquals("Total;;;1.02;1.02;", lines[4])
    }

    /**
     * Gewinne-CSV: Gewinn je Zeile = gerundeter Erlös − gerundeter Einstand — Erlös 2.004 (2.00), Einstand 1.005
     * (1.01), Gewinn exakt 0.999 steht als 0.99, nicht 1.00; die Summe des Gewinns geht so mit den Spalten auf den Cent auf.
     */
    @Test
    fun gainsRowAndTotalAddUp() {
        val texts = TaxCsvTexts(
            coin = "Coin", acquired = "Acq", sold = "Sold", holdingDays = "Days", longTerm = "> 1y", amount = "Amount",
            costUsdt = "Cost", proceedsUsdt = "Proceeds", gainUsdt = "Gain", costTarget = "CostT", proceedsTarget = "ProcT",
            gainTarget = "GainT", date = "Date", valueUsdt = "V", valueTarget = "VT", note = "Note", total = "Total",
            yes = "yes", no = "no", costMissing = "cm", proceedsMissing = "pm", feeLeg = "fl", feeValueMissing = "fv",
            avgCostRates = "avg", valueMissing = "vm", noFx = "nofx", incomplete = "inc",
        )
        val cost = BigDecimal("1.005")
        val proceeds = BigDecimal("2.004")
        val rows = (1..3).map {
            TaxGainRow(
                coin = "SOL", acquiredDate = LocalDate.of(2025, 1, it), soldDate = LocalDate.of(2025, 3, it), holdingDays = 59,
                longTerm = false, amount = BigDecimal.ONE, costUsdt = cost, proceedsUsdt = proceeds, gainUsdt = proceeds - cost,
                costTarget = cost, proceedsTarget = proceeds, gainTarget = proceeds - cost, fxMissing = false, feeLeg = false,
                feeValueMissing = false, method = CostMethod.FIFO,
            )
        }
        val lines = TaxExport.gainsCsv(rows, texts, emptyList()).removePrefix("\uFEFF").split("\r\n")
        assertEquals("SOL;2025-01-01;2025-03-01;59;no;1;1.01;2.00;0.99;1.01;2.00;0.99;", lines[1])
        // 6.00 − 3.03 = 2.97 (exakt summiert und gerundet wären es 3.00)
        assertEquals("Total;;;;;;3.03;6.00;2.97;3.03;6.00;2.97;", lines[4])
    }

    @Test
    fun roundedMoneyMatchesText() {
        assertEquals(BigDecimal("0.34"), CutoffExport.roundedMoney(third))
        assertEquals(BigDecimal("-0.34"), CutoffExport.roundedMoney(third.negate()))
        assertEquals(CutoffExport.money(third), CutoffExport.roundedMoney(third).toPlainString())
    }
}
