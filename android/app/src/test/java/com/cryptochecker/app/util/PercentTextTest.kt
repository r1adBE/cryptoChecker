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
    fun zeichenFuerEingabefelder() {
        assertEquals("%", PercentText.symbol(Locale.US))
        assertEquals("%", PercentText.symbol(Locale.GERMANY))
        // Türkisch: im Feld ebenfalls hinten, nur das Zeichen
        assertEquals("%", PercentText.symbol(Locale.forLanguageTag("tr-TR")))
        assertEquals("\u066A", PercentText.symbol(Locale.forLanguageTag("ar-EG")))
        assertEquals("\u066A", PercentText.symbol(Locale.forLanguageTag("fa-IR")))
        assertEquals("%", PercentText.symbol(Locale.forLanguageTag("ar-u-nu-latn")))
    }

    @Test
    fun abstandNachSpracheUndRegion() {
        // Spanisch (Mexiko) ohne Abstand (CLDR es_MX), die übrigen spanischen Regionen mit
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.forLanguageTag("es-MX")))
        assertEquals("2.4\u00A0%", PercentText.wrap("2.4", Locale.forLanguageTag("es-US")))
        assertEquals("2.4\u00A0%", PercentText.wrap("2.4", Locale.forLanguageTag("es-419")))
        // Englisch in Deutschland, Österreich, Schweden, Finnland, Dänemark mit Abstand; in der Schweiz und den USA ohne
        assertEquals("2,4\u00A0%", PercentText.wrap("2,4", Locale.forLanguageTag("en-DE")))
        assertEquals("2,4\u00A0%", PercentText.wrap("2,4", Locale.forLanguageTag("en-SE")))
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.forLanguageTag("en-CH")))
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.US))
        // Liechtenstein und Westschweiz wie bisher ohne Abstand
        assertEquals("2.4%", PercentText.wrap("2.4", Locale.forLanguageTag("de-LI")))
        assertEquals("2,4%", PercentText.wrap("2,4", Locale.forLanguageTag("fr-CH")))
    }

    @Test
    fun mitteilungsprozentOhneVorzeichenBeiGerundetNull() {
        assertEquals("+1.235%", PriceFormat.changePercentDetailed(1.2346, Locale.US))
        assertEquals("\u22120.500%", PriceFormat.changePercentDetailed(-0.5, Locale.US))
        // Gerundet 0: weder «+0.000%» noch «−0.000%»
        assertEquals("0.000%", PriceFormat.changePercentDetailed(0.0004, Locale.US))
        assertEquals("0.000%", PriceFormat.changePercentDetailed(-0.0004, Locale.US))
        assertEquals("0.000%", PriceFormat.changePercentDetailed(0.0, Locale.US))
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
    fun ohneNullenAmEndeMitMinDecimals() {
        assertEquals("5%", PercentText.unsigned(5.0, 2, Locale.US, minDecimals = 0))
        assertEquals("2,5\u00A0%", PercentText.unsigned(2.5, 4, Locale.GERMANY, minDecimals = 0))
        assertEquals("%0,0125", PercentText.unsigned(0.0125, 4, Locale.forLanguageTag("tr-TR"), minDecimals = 0))
        assertEquals("+12.3%", PercentText.signed(12.34, 1, Locale.US, minDecimals = 0))
        // ohne minDecimals wie bisher genau [decimals] Stellen
        assertEquals("5.00%", PercentText.unsigned(5.0, 2, Locale.US))
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
