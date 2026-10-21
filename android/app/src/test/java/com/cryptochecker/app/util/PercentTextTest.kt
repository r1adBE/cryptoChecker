package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Zentrale Prozent-Schreibweise ([PercentText]) und kompakter Abstand ([PriceFormat.age]). */
class PercentTextTest {

    @Test
    fun zeichenJeSprache() {
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.US))
        assertEquals("2,4\u00A0%", PercentText.wrap("2,4", Locale.GERMANY))
        // Schweiz: ohne Abstand (CLDR de_CH)
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.forLanguageTag("de-CH")))
        assertEquals("2,4\u00A0%", PercentText.wrap("2,4", Locale.FRANCE))
        assertEquals("%2,4", PercentText.wrap("2,4", Locale.forLanguageTag("tr-TR")))
        assertEquals("\u0662\u066B\u0664\u066A", PercentText.wrap("\u0662\u066B\u0664", Locale.forLanguageTag("ar-EG")))
        assertEquals("\u06F2\u066B\u06F4\u066A", PercentText.wrap("\u06F2\u066B\u06F4", Locale.forLanguageTag("fa-IR")))
        // Arabisch mit lateinischen Ziffern: gewöhnliches «%»
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.forLanguageTag("ar-u-nu-latn")))
    }

    @Test
    fun vorzeichenVorDemGanzen() {
        assertEquals("+2.40%", PercentText.signed(2.4, 2, Locale.US))
        assertEquals("\u22121.4%", PercentText.signed(-1.36, 1, Locale.US))
        assertEquals("+%2,4", PercentText.signed(2.4, 1, Locale.forLanguageTag("tr-TR")))
        // Gerundet 0: ohne Vorzeichen
        assertEquals("0.00%", PercentText.signed(-0.004, 2, Locale.US))
        assertEquals("2.50%", PercentText.unsigned(-2.5, 2, Locale.US))
    }

    @Test
    fun abstandGroessteEinheit() {
        val now = 10_000_000_000L
        assertEquals(7L to PriceFormat.AgeUnit.DAYS, PriceFormat.ageParts(now - 7 * 86_400_000L - 5_000, now))
        assertEquals(3L to PriceFormat.AgeUnit.HOURS, PriceFormat.ageParts(now - 3 * 3_600_000L, now))
        assertEquals(12L to PriceFormat.AgeUnit.MINUTES, PriceFormat.ageParts(now - 12 * 60_000L, now))
        assertEquals(45L to PriceFormat.AgeUnit.SECONDS, PriceFormat.ageParts(now - 45_000L, now))
        assertNull(PriceFormat.ageParts(0L, now))
        assertNull(PriceFormat.ageParts(now + 1_000, now))
    }

    @Test
    fun abstandEnglischUnveraendert() {
        val now = 10_000_000_000L
        // Englisch wie bisher «7d» (ICU NARROW bzw. ohne ICU der Ersatz)
        assertEquals("7d", PriceFormat.age(now - 7 * 86_400_000L, now, Locale.US))
        assertEquals("45s", PriceFormat.age(now - 45_000L, now, Locale.US))
        // Ersatz in der Sprache: arabische Ziffern
        assertEquals("\u0667d", PriceFormat.ageFallback(7, PriceFormat.AgeUnit.DAYS, Locale.forLanguageTag("ar-EG")))
    }
}
