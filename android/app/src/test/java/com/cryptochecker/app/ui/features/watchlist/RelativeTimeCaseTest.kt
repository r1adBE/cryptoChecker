package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Relative Zeit mitten im Satz ([midSentence]): klein beginnend, nur das erste Zeichen, nach den Regeln der Sprache.
 * Am Zeilenanfang ([lineStart]) wieder gross. Wie iOS `RelativeTimeLineStartTests`.
 */
class RelativeTimeCaseTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test
    fun germanStartsLowercase() {
        assertEquals("vor 10 Min.", midSentence("Vor 10 Min.", 10 * minute, Locale.GERMAN))
        assertEquals("gestern", midSentence("Gestern", day, Locale.GERMAN))
    }

    @Test
    fun onlyTheFirstCharacter() {
        // Abkürzungen und Wörter dahinter bleiben, wie sie sind
        assertEquals("vor 2 Std.", midSentence("Vor 2 Std.", 2 * 60 * minute, Locale.GERMAN))
        assertEquals("10 min. ago", midSentence("10 min. ago", 10 * minute, Locale.ENGLISH))
    }

    @Test
    fun followsTheLanguageRules() {
        // Türkisch: «İ» wird zu «i» (nicht «i̇»)
        assertEquals("i", midSentence("İ", 10 * minute, Locale.forLanguageTag("tr")))
        // Schriften ohne Gross/Klein bleiben unverändert
        assertEquals("10 分钟前", midSentence("10 分钟前", 10 * minute, Locale.CHINESE))
        assertEquals("قبل ١٠ دقائق", midSentence("قبل ١٠ دقائق", 10 * minute, Locale.forLanguageTag("ar")))
    }

    @Test
    fun datesFromOneWeekStayAsTheyAre() {
        // Ab einer Woche liefert Android ein Datum: Monatsnamen nicht verkleinern
        assertEquals("Oct 12", midSentence("Oct 12", 7 * day, Locale.ENGLISH))
        assertEquals("vor 6 Tagen", midSentence("Vor 6 Tagen", 6 * day, Locale.GERMAN))
        // Uhr verstellt (Zeit in der Zukunft): gleiche Grenze
        assertEquals("Oct 30", midSentence("Oct 30", -8 * day, Locale.ENGLISH))
    }

    @Test
    fun lineStartingWithTheTimeStartsUppercase() {
        // «vor 5 Min. · Binance nicht erreichbar» → gross am Zeilenanfang, Rest unverändert
        val age = midSentence("Vor 5 Min.", 5 * minute, Locale.GERMAN)
        assertEquals(
            "Vor 5 Min. · Binance nicht erreichbar",
            lineStart("$age · Binance nicht erreichbar", age, Locale.GERMAN),
        )
        assertEquals("Vor 2 Std.", lineStart("vor 2 Std.", "vor 2 Std.", Locale.GERMAN))
        assertEquals(
            "Il y a 5 min · Binance injoignable",
            lineStart("il y a 5 min · Binance injoignable", "il y a 5 min", Locale.FRENCH),
        )
        // Türkisch: «i» wird zu «İ»
        assertEquals("İ", lineStart("i", "i", Locale.forLanguageTag("tr")))
        // Schriften ohne Gross/Klein bleiben unverändert
        assertEquals("10 分钟前 · 无法连接 Binance", lineStart("10 分钟前 · 无法连接 Binance", "10 分钟前", Locale.CHINESE))
    }

    @Test
    fun timeInsideTheLineStaysLowercase() {
        // Andere Wortstellung bzw. Zeit hinter anderem Text: nichts ändern
        assertEquals("Binance · vor 4 Min.", lineStart("Binance · vor 4 Min.", "vor 4 Min.", Locale.GERMAN))
        assertEquals("bitFlyer · vor 4 Min.", lineStart("bitFlyer · vor 4 Min.", "vor 4 Min.", Locale.GERMAN))
        assertEquals("", lineStart("", "", Locale.GERMAN))
        assertEquals("abc", lineStart("abc", "", Locale.GERMAN))
    }
}
