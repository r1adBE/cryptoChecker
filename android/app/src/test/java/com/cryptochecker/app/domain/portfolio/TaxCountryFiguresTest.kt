package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import org.junit.Assert.assertEquals
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
        ),
    )

    @Test
    fun noTemplateReviewedYet() {
        assertTrue(TaxCountryFigures.reviewed.isEmpty())
    }

    @Test
    fun unreviewedTemplateKeepsOnlyNeutralSums() {
        for (t in listOf(TaxTemplate.DE, TaxTemplate.AT, TaxTemplate.CH)) {
            assertEquals(listOf(TaxLine.NET), TaxCountryFigures.shown(summary(t)).lines.map { it.line })
        }
    }
}
