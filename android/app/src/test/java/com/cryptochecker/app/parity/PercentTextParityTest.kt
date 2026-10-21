package com.cryptochecker.app.parity

import com.cryptochecker.app.util.PercentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Prozent in der Schreibweise der Sprache (`testdata/parity/percent_text.json`): Abstand vor «%» (de, fr …, nicht
 * de_CH/fr_CH), Türkisch «%2,4», Arabisch/Persisch «٪» mit eigenen Ziffern. Gegenstück: `Tests/PercentTextParityTests.swift`.
 */
class PercentTextParityTest {

    @Test
    fun faelleWieGemeinsameTestfaelle() {
        val cases = ParityJson.load("percent_text.json")["cases"].list()
        for (raw in cases) {
            val c = raw.obj()
            val locale = Locale.forLanguageTag((c["locale"] as String).replace('_', '-'))
            val value = c["value"].num()!!
            val decimals = c["decimals"].num()!!.toInt()
            val actual = when (c["kind"]) {
                "signed" -> PercentText.signed(value, decimals, locale)
                else -> PercentText.unsigned(value, decimals, locale)
            }
            assertEquals("${c["locale"]} ${c["kind"]} $value", c["expected"], actual)
        }
        // de, fr, tr, ar, fa müssen dabei sein (die Fälle mit eigener Schreibweise)
        val locales = cases.map { (it.obj()["locale"] as String).substringBefore('_') }.toSet()
        assertTrue(locales.containsAll(listOf("de", "fr", "tr", "ar", "fa")))
    }
}
