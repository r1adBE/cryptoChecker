package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kleine, graue Währung hinter Kurs und Betrag in den Widgets ([WidgetQuoteText]). */
class WidgetQuoteTextTest {

    @Test
    fun findsQuoteAfterNumber() {
        assertEquals(10..13, WidgetQuoteText.quoteRange("67’000.00 USDT", "USDT"))
        assertEquals(10..12, WidgetQuoteText.quoteRange("↘ −968.40 CHF · 24h", "CHF"))
        assertEquals(12..15, WidgetQuoteText.quoteRange("≈ 92’310.00 USDT · ohne 1 Coin", "USDT"))
    }

    @Test
    fun ignoresQuoteWithoutNumberOrInsideWords() {
        // Verborgen oder ohne Kurs: nichts zu verkleinern
        assertNull(WidgetQuoteText.quoteRange("•••", "CHF"))
        assertNull(WidgetQuoteText.quoteRange("—", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("USDT", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("12.00 USDTX", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("12.00 USDT", ""))
    }

    @Test
    fun takesLastOccurrence() {
        // «USD» kommt auch in «USDT» vor: nur das eigenständige Wort am Ende zählt
        assertEquals(9..11, WidgetQuoteText.quoteRange("1 USDT 2 USD", "USD"))
    }

    @Test
    fun bidiIsolatesDoNotHideQuote() {
        val text = "⁦+12.00 CHF⁩"
        assertEquals(8..10, WidgetQuoteText.quoteRange(text, "CHF"))
    }

    @Test
    fun scaleKeepsMinimumSize() {
        assertEquals(0.42f, WidgetQuoteText.scale(28f), 1e-4f)
        // 14 sp × 0.42 < 11 sp → 11 sp
        assertEquals(11f / 14f, WidgetQuoteText.scale(14f), 1e-4f)
        assertEquals(1f, WidgetQuoteText.scale(8f), 1e-4f)
        assertEquals(1f, WidgetQuoteText.scale(0f), 1e-4f)
    }

    @Test
    fun widthMeasuresQuoteSmaller() {
        // Messung: Zeichen × Grösse
        val measure: (String, Float) -> Float = { s, sp -> s.length * sp }
        // «12 » in 20, «USDT» in max(20 × 0.42, 11) = 11
        assertEquals(3 * 20f + 4 * 11f, WidgetQuoteText.width("12 USDT", "USDT", 20f, measure), 1e-4f)
        // Ohne Währung im Text: alles in voller Grösse
        assertEquals(3 * 20f, WidgetQuoteText.width("•••", "USDT", 20f, measure), 1e-4f)
    }
}
