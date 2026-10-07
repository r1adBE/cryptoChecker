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
        // 2 × 2 (152 dp): 152 − 46 − 60 − 2 = 44 dp für das Paar → «BTC» (24 dp), nicht «BTC/USDT» (64 dp)
        assertEquals("BTC", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 152, 60f, measure))
        // 4 × 2 (312 dp): volles Paar
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 312, 60f, measure))
        // Unbekannte Breite: volles Paar
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 0, 60f, measure))
        // Grenze: genau passend (46 + 60 + 2 + 64 = 172)
        assertEquals("BTC/USDT", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 172, 60f, measure))
        assertEquals("BTC", WidgetTextFit.singlePairLabel("BTC/USDT", "BTC", 171, 60f, measure))
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
}
