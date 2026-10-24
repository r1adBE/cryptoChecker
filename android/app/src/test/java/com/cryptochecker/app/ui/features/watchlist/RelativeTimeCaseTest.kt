package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Relative Zeit mitten im Satz ([midSentence]): klein beginnend, nur das erste Zeichen, nach den Regeln der Sprache. */
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
}
