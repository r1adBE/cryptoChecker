package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTextFitTest {

    /** Grobe Breite: 8 dp je Zeichen. */
    private val measure: (String) -> Float = { it.length * 8f }

    @Test
    fun firstFittingTakesLongestThatFits() {
        assertEquals("BTC/USDT", WidgetTextFit.firstFitting(listOf("BTC/USDT", "BTC"), 64f, measure))
        assertEquals("BTC", WidgetTextFit.firstFitting(listOf("BTC/USDT", "BTC"), 63f, measure))
        // Passt nichts: der kürzeste
        assertEquals("BTC", WidgetTextFit.firstFitting(listOf("BTC/USDT", "BTC"), 5f, measure))
        // Unbekannter Platz: der erste
        assertEquals("BTC/USDT", WidgetTextFit.firstFitting(listOf("BTC/USDT", "BTC"), null, measure))
        // Leere und doppelte Einträge zählen nicht
        assertEquals("BTC", WidgetTextFit.firstFitting(listOf("", "BTC", "BTC"), 1f, measure))
        assertEquals("", WidgetTextFit.firstFitting(emptyList(), 100f, measure))
    }

    @Test
    fun singlePairFallsBackToBaseWhenTight() {
        // 2 × 2 (152 dp): 152 − 44 − 60 − 2 = 46 dp für das Paar → «BTC» (24 dp), nicht «BTC/USDT» (64 dp)
        assertEquals("BTC", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 152, 60f, measure))
        // 4 × 2 (312 dp): volles Paar
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 312, 60f, measure))
        // Unbekannte Breite: volles Paar
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 0, 60f, measure))
        // Grenze: genau passend (44 + 60 + 2 + 64 = 170)
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 170, 60f, measure))
        assertEquals("BTC", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 169, 60f, measure))
    }

    @Test
    fun todayShowsPercentOnlyWhenTight() {
        val full = "heute ▲ +997.62 CHF · +1.24%" // 28 Zeichen = 224 dp
        val short = "heute ▲ +1.24%" // 14 Zeichen = 112 dp
        assertEquals(full, WidgetTextFit.todayText(full, short, 226f, measure))
        assertEquals(short, WidgetTextFit.todayText(full, short, 225f, measure))
        assertEquals(short, WidgetTextFit.todayText(full, short, 114f, measure))
        // Ohne Prozentwert bleibt der volle Text
        assertEquals(full, WidgetTextFit.todayText(full, null, 50f, measure))
        // Unbekannte Breite: voll
        assertEquals(full, WidgetTextFit.todayText(full, short, null, measure))
    }

    @Test
    fun changeLineDropsPeriodThenAmountKeepsPercent() {
        val list = WidgetTextFit.changeLineCandidates("↘", "−31’997.28 CHF", "−27.85%", "1J")
        assertEquals(
            listOf(
                "↘ −31’997.28 CHF  −27.85% · 1J",
                "↘ −31’997.28 CHF  −27.85%",
                "↘ −27.85% · 1J",
                "↘ −27.85%",
            ),
            list,
        )
        // Eng: zuerst ohne Zeitraum, dann nur das Prozent
        assertEquals("↘ −31’997.28 CHF  −27.85%", WidgetTextFit.firstFitting(list, 26 * 8f, measure))
        assertEquals("↘ −27.85% · 1J", WidgetTextFit.firstFitting(list, 20 * 8f, measure))
        assertEquals("↘ −27.85%", WidgetTextFit.firstFitting(list, 5f, measure))
    }

    @Test
    fun changeLineWithoutPercentOrArrow() {
        assertEquals(
            listOf("↗ +12.00 CHF · 24h", "↗ +12.00 CHF"),
            WidgetTextFit.changeLineCandidates("↗", "+12.00 CHF", null, "24h"),
        )
        // 0: kein Pfeil; ohne Zeitraum keine Varianten mit «·»
        assertEquals(
            listOf("0.00 CHF  0.00%", "0.00%"),
            WidgetTextFit.changeLineCandidates("", "0.00 CHF", "0.00%", null),
        )
    }

    @Test
    fun sizedFitShrinksBeforeDroppingParts() {
        // Breite = Zeichen × Schriftgrösse ÷ 2
        val sized: (String, Float) -> Float = { text, sp -> text.length * sp / 2f }
        val list = listOf("A".repeat(20), "A".repeat(10))
        val sizes = WidgetTextFit.CHANGE_LINE_SIZES_SP
        // 20 Zeichen: 12 sp = 120, 11 sp = 110 (J-18: nie unter 11 sp)
        assertEquals(listOf(12f, 11f), sizes)
        assertEquals("A".repeat(20) to 12f, WidgetTextFit.firstFittingSized(list, sizes, 120f, sized))
        assertEquals("A".repeat(20) to 11f, WidgetTextFit.firstFittingSized(list, sizes, 115f, sized))
        assertEquals("A".repeat(20) to 11f, WidgetTextFit.firstFittingSized(list, sizes, 110f, sized))
        // Erst dann der kürzere Kandidat, wieder ab 12 sp
        assertEquals("A".repeat(10) to 12f, WidgetTextFit.firstFittingSized(list, sizes, 109f, sized))
        // Passt nichts: der kürzeste in der kleinsten Grösse; unbekannt: der erste in der ersten
        assertEquals("A".repeat(10) to 11f, WidgetTextFit.firstFittingSized(list, sizes, 1f, sized))
        assertEquals("A".repeat(20) to 12f, WidgetTextFit.firstFittingSized(list, sizes, null, sized))
        assertEquals("" to 12f, WidgetTextFit.firstFittingSized(emptyList(), sizes, 50f, sized))
    }

    @Test
    fun periodStartMarksGreySuffix() {
        val text = "↘ −31’997.28 CHF  −27.85% · 1J"
        val start = WidgetTextFit.periodStart(text, "1J")
        assertEquals("· 1J", start?.let { text.substring(it) })
        assertEquals(null, WidgetTextFit.periodStart("↘ −27.85%", "1J"))
        assertEquals(null, WidgetTextFit.periodStart(text, null))
        assertEquals(null, WidgetTextFit.periodStart(text, ""))
    }
}
