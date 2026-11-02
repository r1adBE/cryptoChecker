package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Länder-Schlüsse erst nach Fachprüfung ([TaxCountryFigures]); gleiche Regel wie iOS `TaxCountryFiguresTests`. */
class TaxCountryFiguresTest {
    private fun summary(template: TaxTemplate) = TaxYearSummary(
        template, TaxMethod.FIFO, template.period(2025), "EUR", true,
        listOf(
            TaxSummaryLine(TaxLine.NET, BigDecimal.TEN, BigDecimal.TEN),
            TaxSummaryLine(TaxLine.DE_PRIVATE_LIMIT, null, BigDecimal("1000"), TaxLimitStatus.BELOW),
            TaxSummaryLine(TaxLine.AT_RATE, BigDecimal.ONE, BigDecimal.ONE),
            // «Vermögenswert 31.12. (Orientierung)» trägt immer den ESTV-Vermerk, ohne orientation-Flag
            TaxSummaryLine(TaxLine.CH_WEALTH_VALUE, BigDecimal.ONE, BigDecimal.ONE, note = TaxSummaryNote.CH_WEALTH_ORIENTATION),
        ),
        wealth = TaxWealth(LocalDate.of(2025, 12, 31), emptyList(), BigDecimal.ONE, BigDecimal.ONE),
    )

    private val texts = CutoffCsvTexts(
        "Coin", "Menge", "Kurs", "Wert", "Kurs USD→CHF", "Wert CHF", "Hinweis", "Total", "–", "–", "ungeprüft",
        "unvollständig", "%1\$s %2\$s %3\$s", estvNoteFormat = "ESTV %1\$s: %2\$s",
    )

    @Test
    fun noTemplateReviewedYet() {
        assertTrue(TaxCountryFigures.reviewed.isEmpty())
    }

    @Test
    fun unreviewedTemplateKeepsOnlyNeutralSums() {
        for (t in listOf(TaxTemplate.DE, TaxTemplate.AT, TaxTemplate.CH)) {
            val shown = TaxCountryFigures.shown(summary(t))
            assertEquals(listOf(TaxLine.NET), shown.lines.map { it.line })
            // Ohne CH-Vermögenswert auch keine Werte je Coin (Karte und Übersicht-CSV)
            assertNull(shown.wealth)
        }
    }

    /** Gewinne, Einkünfte, Bestand, Übersicht: keine Kopfzeile «Kursliste ESTV», kein ESTV-Hinweis im Bestand. */
    @Test
    fun unreviewedChCsvHasNoEstvLines() {
        val header = listOf("Title", "Info", "Disclaimer")
        val note = "Wealth tax not calculated – ESTV price list: https://example"
        assertEquals(header, TaxCountryFigures.withChWealthNote(TaxTemplate.CH, header, note))
        assertNull(TaxCountryFigures.holdingsTexts(TaxTemplate.CH, texts).estvNoteFormat)
        assertTrue(TaxCountryFigures.isCountryLine(TaxLine.CH_WEALTH_VALUE))
        assertTrue(TaxCountryFigures.isCountryLine(TaxLine.CH_WEALTH))
        assertFalse(TaxCountryFigures.isCountryLine(TaxLine.NET))
    }
}
