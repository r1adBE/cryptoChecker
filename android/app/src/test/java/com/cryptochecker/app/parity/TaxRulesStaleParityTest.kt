package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.TaxRulesDate
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * Regelstand der Länder-Vorlagen als gemeinsame Testfälle (`testdata/parity/tax_rules_stale.json`, Kopie in iOS
 * `Tests/Parity/`, gelesen von `TaxRulesStaleParityTests`): Lesen von «JJJJ-MM» und Hinweis «Regeln
 * womöglich veraltet» für Steuerjahre nach dem Jahr des Regelstands.
 */
class TaxRulesStaleParityTest {

    private val data = ParityJson.load("tax_rules_stale.json")

    @Test
    fun month() {
        val cases = data["month"].list()
        assertTrue(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val rulesDate = c["rulesDate"] as String
            val want = (c["month"] as List<*>?)?.let { YearMonth.of(it[0].num()!!.toInt(), it[1].num()!!.toInt()) }
            assertEquals("month «$rulesDate»", want, TaxRulesDate.month(rulesDate))
        }
    }

    @Test
    fun rulesStale() {
        val cases = data["stale"].list()
        assertTrue(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val got = TaxRulesDate.rulesStale(c["taxYear"].num()!!.toInt(), c["rulesDate"] as String)
            assertEquals(c["name"] as String, c["stale"], got)
        }
    }

    /** Der hinterlegte Regelstand ist lesbar — sonst bliebe der Hinweis still aus. */
    @Test
    fun storedRulesDateIsReadable() {
        val month = requireNotNull(TaxRulesDate.month(TaxTemplate.RULES_DATE)) {
            "RULES_DATE «${TaxTemplate.RULES_DATE}» nicht lesbar"
        }
        assertTrue(TaxRulesDate.rulesStale(month.year + 1))
        assertEquals(false, TaxRulesDate.rulesStale(month.year))
    }
}
