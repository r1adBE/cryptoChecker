package com.cryptochecker.app.domain.portfolio

import java.time.YearMonth

/**
 * Regelstand der Länder-Vorlagen ([TaxTemplate.RULES_DATE], «JJJJ-MM»): Sätze, Freibeträge und Grenzen (z. B.
 * [TaxTemplate.CA_INCLUSION], [TaxTemplate.FR_EXEMPT_EUR]) sind fest hinterlegt. Für ein Steuerjahr nach dem Jahr des
 * Regelstands können sie veraltet sein — die Seite «Steuer-Arbeitsunterlage» weist dann darauf hin, ohne zu
 * blockieren. Wie iOS `TaxRulesDate`; gemeinsame Fälle in `tax_rules_stale.json`.
 */
object TaxRulesDate {

    /** «JJJJ-MM» mit lateinischen Ziffern (`\d` in Java-Regex: nur 0–9). */
    private val FORMAT = Regex("""(\d{4})-(\d{2})""")

    /** Monat des Regelstands; null = nicht «JJJJ-MM» oder Monat ausserhalb 1–12. */
    fun month(rulesDate: String): YearMonth? {
        val m = FORMAT.matchEntire(rulesDate.trim()) ?: return null
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return null
        return YearMonth.of(m.groupValues[1].toInt(), month)
    }

    /**
     * Liegt das Steuerjahr [taxYear] (Startjahr, wie [TaxTemplate.period]) nach dem Jahr von [rulesDate]?
     * Unlesbarer Regelstand: nein (der hinterlegte [TaxTemplate.RULES_DATE] ist im Test als lesbar geprüft).
     */
    fun rulesStale(taxYear: Int, rulesDate: String = TaxTemplate.RULES_DATE): Boolean {
        val rules = month(rulesDate) ?: return false
        return taxYear > rules.year
    }
}
