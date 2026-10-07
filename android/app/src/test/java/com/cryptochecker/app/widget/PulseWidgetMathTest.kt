package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PulseWidgetMathTest {

    private fun report(btc: Double, eth: Double, sol: Double): PulseReport =
        CryptoPulse.evaluate(PulseInput(btc, eth, sol, null, null, null, null, 1L))!!

    @Test
    fun directionAndGlyphFollowSummary() {
        assertEquals(1, PulseWidgetMath.direction(PulseSummary.BROAD_UP))
        assertEquals(1, PulseWidgetMath.direction(PulseSummary.BROAD_UP_VOLUME))
        assertEquals(-1, PulseWidgetMath.direction(PulseSummary.BROAD_DOWN_VOLUME))
        assertEquals(0, PulseWidgetMath.direction(PulseSummary.MIXED))
        assertEquals("▲", PulseWidgetMath.glyph(report(2.8, 2.0, 3.0).summary))
        assertEquals("▼", PulseWidgetMath.glyph(report(-2.8, -2.0, -3.0).summary))
        assertNull(PulseWidgetMath.glyph(report(0.2, -0.1, 0.3).summary))
    }

    @Test
    fun chipsOnlyWhenTheyFitCompletely() {
        // Je Chip 64 dp Text + 14 Innenrand = 78, Abstand 6; Rand 24 + 2 Sicherheit
        val widths = listOf(64f, 64f, 64f)
        // 3 Chips: 3 × 78 + 2 × 6 = 246 → ab 272 dp
        assertEquals(3, PulseWidgetMath.coinCount(272, widths))
        // 3 × 2 (~232 dp): SOL würde abgeschnitten → nur BTC und ETH
        assertEquals(2, PulseWidgetMath.coinCount(232, widths))
        assertEquals(2, PulseWidgetMath.coinCount(271, widths))
        // 2 × 2 (~152 dp): BTC
        assertEquals(1, PulseWidgetMath.coinCount(152, widths))
        // Sehr schmal oder unbekannt: immer BTC
        assertEquals(1, PulseWidgetMath.coinCount(60, widths))
        assertEquals(1, PulseWidgetMath.coinCount(0, widths))
        val r = report(2.8, 1.2, -0.4)
        assertEquals(listOf("BTC", "ETH"), PulseWidgetMath.coins(r, 2).map { it.symbol })
        assertEquals(-0.4, PulseWidgetMath.coins(r, 3)[2].change, 1e-9)
    }

    @Test
    fun titleShrinksOrHidesNeverEllipsizes() {
        // «Was gerade auffällt»: 13 sp = 130 dp, 11 sp = 110 dp (Text wächst mit der Grösse)
        val measure: (Float) -> Float = { it * 10f }
        // Platz = Breite − 24 − 22 − 2
        assertEquals(13f, PulseWidgetMath.titleSizeSp(178, measure))
        assertEquals(11f, PulseWidgetMath.titleSizeSp(177, measure))
        assertEquals(11f, PulseWidgetMath.titleSizeSp(158, measure))
        assertNull(PulseWidgetMath.titleSizeSp(152, measure))
        assertEquals(13f, PulseWidgetMath.titleSizeSp(0, measure))
    }

    @Test
    fun headlineShrinksThenWraps() {
        // «Breite Stärke»: 18 sp = 126 dp, 16 sp = 112, 14 sp = 98 (7 dp je sp)
        val measure: (Float) -> Float = { it * 7f }
        // Mit ▲: Platz = Breite − 24 − 16 − 2
        assertEquals(PulseWidgetMath.HeadlineStyle(18f, 1), PulseWidgetMath.headlineStyle(168, true, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(16f, 1), PulseWidgetMath.headlineStyle(154, true, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 1), PulseWidgetMath.headlineStyle(140, true, measure))
        // 2 × 2 eng: zweizeilig in 14 sp statt «Breite Stä…»
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 2), PulseWidgetMath.headlineStyle(139, true, measure))
        // Ohne Zeichen mehr Platz
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 1), PulseWidgetMath.headlineStyle(124, false, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(18f, 1), PulseWidgetMath.headlineStyle(0, true, measure))
    }

    @Test
    fun chipTextLikeCard() {
        val en = Locale.US
        assertEquals("BTC ▲ +2.8%", PulseWidgetMath.chipText(PulseWidgetCoin("BTC", "Bitcoin", 2.84), en))
        assertEquals("SOL ▼ −1.4%", PulseWidgetMath.chipText(PulseWidgetCoin("SOL", "Solana", -1.36), en))
        // Praktisch unverändert: ohne Pfeil, «0.0%»
        assertEquals("ETH 0.0%", PulseWidgetMath.chipText(PulseWidgetCoin("ETH", "Ethereum", 0.049), en))
        assertTrue(PulseWidgetMath.isFlat(-0.04))
        assertFalse(PulseWidgetMath.isFlat(0.05))
        assertEquals(0.0, PulseWidgetMath.spokenChange(0.03), 0.0)
        assertEquals(-2.0, PulseWidgetMath.spokenChange(-2.0), 0.0)
    }

    @Test
    fun ageHeightRules() {
        val now = 100L * 3_600_000L
        assertTrue(PulseWidgetMath.isShowable(now - 5 * 60_000L, now))
        assertTrue(PulseWidgetMath.isShowable(now - PulseWidgetMath.MAX_AGE_MILLIS, now))
        assertFalse(PulseWidgetMath.isShowable(now - PulseWidgetMath.MAX_AGE_MILLIS - 1, now))
        assertFalse(PulseWidgetMath.isShowable(now + 1, now))
        assertFalse(PulseWidgetMath.isShowable(0L, now))
        assertEquals(2, PulseWidgetMath.leadLines(0))
        assertEquals(2, PulseWidgetMath.leadLines(180))
        assertEquals(1, PulseWidgetMath.leadLines(120))
        // Zweizeilige Schlagzeile: zwei Zeilen Leitsatz erst mit mehr Höhe
        assertEquals(1, PulseWidgetMath.leadLines(160, headlineLines = 2))
        assertEquals(2, PulseWidgetMath.leadLines(169, headlineLines = 2))
        assertTrue(PulseWidgetMath.showsTime(150))
        assertFalse(PulseWidgetMath.showsTime(149))
    }
}
