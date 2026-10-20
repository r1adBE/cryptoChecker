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
        assertEquals("↗", PulseWidgetMath.glyph(report(2.8, 2.0, 3.0).summary))
        assertEquals("↘", PulseWidgetMath.glyph(report(-2.8, -2.0, -3.0).summary))
        assertNull(PulseWidgetMath.glyph(report(0.2, -0.1, 0.3).summary))
    }

    @Test
    fun chipsOnlyWhenTheyFitCompletely() {
        // Je Coin 64 dp Text (keine Pille, kein Innenrand), Abstand 8; Rand 24 + 2 Sicherheit
        val widths = listOf(64f, 64f, 64f)
        // 3 Coins: 3 × 64 + 2 × 8 = 208 → ab 234 dp
        assertEquals(3, PulseWidgetMath.coinCount(234, widths))
        // Knapp darunter würde SOL abgeschnitten → nur BTC und ETH
        assertEquals(2, PulseWidgetMath.coinCount(233, widths))
        // 2 Coins: 2 × 64 + 8 = 136 → ab 162 dp
        assertEquals(2, PulseWidgetMath.coinCount(162, widths))
        assertEquals(1, PulseWidgetMath.coinCount(161, widths))
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
        // «Was gerade auffällt»: 14 sp = 140 dp, 12 sp = 120 dp (Text wächst mit der Grösse; App-Stufen body/label)
        val measure: (Float) -> Float = { it * 10f }
        // Platz = Breite − 24 − 20 − 2
        assertEquals(14f, PulseWidgetMath.titleSizeSp(186, measure))
        assertEquals(12f, PulseWidgetMath.titleSizeSp(185, measure))
        assertEquals(12f, PulseWidgetMath.titleSizeSp(166, measure))
        assertNull(PulseWidgetMath.titleSizeSp(165, measure))
        assertEquals(14f, PulseWidgetMath.titleSizeSp(0, measure))
    }

    @Test
    fun headlineShrinksThenWraps() {
        // «Breite Stärke»: 16 sp = 112 dp, 14 sp = 98 (7 dp je sp; App-Stufen title/body)
        val measure: (Float) -> Float = { it * 7f }
        // Mit ↗: Platz = Breite − 24 − 14 − 2
        assertEquals(PulseWidgetMath.HeadlineStyle(16f, 1), PulseWidgetMath.headlineStyle(152, true, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 1), PulseWidgetMath.headlineStyle(151, true, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 1), PulseWidgetMath.headlineStyle(138, true, measure))
        // 2 × 2 eng: zweizeilig in 14 sp statt «Breite Stä…»
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 2), PulseWidgetMath.headlineStyle(137, true, measure))
        // Ohne Zeichen mehr Platz
        assertEquals(PulseWidgetMath.HeadlineStyle(14f, 1), PulseWidgetMath.headlineStyle(124, false, measure))
        assertEquals(PulseWidgetMath.HeadlineStyle(16f, 1), PulseWidgetMath.headlineStyle(0, true, measure))
    }

    @Test
    fun chipTextLikeCard() {
        val en = Locale.US
        assertEquals("BTC ↗ +2.8%", PulseWidgetMath.chipText(PulseWidgetCoin("BTC", "Bitcoin", 2.84), en))
        assertEquals("SOL ↘ −1.4%", PulseWidgetMath.chipText(PulseWidgetCoin("SOL", "Solana", -1.36), en))
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

    @Test
    fun fearGreedFromNewestCacheWithin24Hours() {
        val now = 1_800_000_000_000L
        val h = 3_600_000L
        // Jüngerer Wert gewinnt (Markt-Tab-Eintrag vor älterer Pulse-Eingabe und umgekehrt)
        assertEquals(72, PulseWidgetMath.fearGreed(72, now - h, 60, now - 3 * h, now))
        assertEquals(60, PulseWidgetMath.fearGreed(72, now - 5 * h, 60, now - 3 * h, now))
        // Nur einer vorhanden
        assertEquals(41, PulseWidgetMath.fearGreed(null, null, 41, now - h, now))
        // Älter als 24 h, in der Zukunft oder ausserhalb 0–100: keine Zeile
        assertNull(PulseWidgetMath.fearGreed(72, now - 24 * h - 1, null, null, now))
        assertEquals(72, PulseWidgetMath.fearGreed(72, now - 24 * h, null, null, now))
        assertNull(PulseWidgetMath.fearGreed(72, now + h, null, null, now))
        assertNull(PulseWidgetMath.fearGreed(140, now - h, null, null, now))
        assertNull(PulseWidgetMath.fearGreed(null, null, null, null, now))
    }

    @Test
    fun fearGreedLineOnlyWithRoom() {
        // Mittel/gross: Platz unter Leitsatz und Stand
        assertTrue(PulseWidgetMath.showsFearGreed(250, 180, headlineLines = 1, textWidthDp = 130f))
        // Klein (2×2): unverändert ohne Zeile
        assertFalse(PulseWidgetMath.showsFearGreed(150, 150, headlineLines = 1, textWidthDp = 130f))
        assertFalse(PulseWidgetMath.showsFearGreed(250, 167, headlineLines = 1, textWidthDp = 130f))
        assertTrue(PulseWidgetMath.showsFearGreed(250, 168, headlineLines = 1, textWidthDp = 130f))
        // Zweizeilige Schlagzeile braucht mehr Höhe
        assertFalse(PulseWidgetMath.showsFearGreed(250, 180, headlineLines = 2, textWidthDp = 130f))
        // Zu schmal für den ganzen Text: nie abgeschnitten
        assertFalse(PulseWidgetMath.showsFearGreed(140, 220, headlineLines = 1, textWidthDp = 130f))
        // Unbekannte Grösse: zeigen
        assertTrue(PulseWidgetMath.showsFearGreed(0, 0, headlineLines = 1, textWidthDp = 130f))
    }

    @Test
    fun `Fear and Greed und Marktbreite - breit eine Zeile, mittel zwei Zeilen, wenig Hoehe wie bisher`() {
        // Gemessene Breiten (dp): Fear & Greed 120, Marktbreite 130, beides mit « · » 260
        fun layout(width: Int, height: Int, headlineLines: Int = 1, fng: Float? = 120f, breadth: Float? = 130f) =
            PulseWidgetMath.sideLayout(
                width, height, headlineLines,
                fearGreedWidthDp = fng,
                breadthWidthDp = breadth,
                bothWidthDp = if (fng != null && breadth != null) 260f else null,
            )
        // Breit: beides in einer Zeile
        assertEquals(PulseSideLayout.ONE_LINE, layout(400, 200))
        // Mittel (250 dp, 224 dp nutzbar): untereinander in zwei Zeilen
        assertEquals(PulseSideLayout.TWO_LINES, layout(250, 200))
        assertEquals(PulseSideLayout.TWO_LINES, layout(250, 186))
        // Höhe nur für eine Nebenzeile: wie bisher zuerst Fear & Greed
        assertEquals(PulseSideLayout.FEAR_GREED, layout(250, 185))
        assertEquals(PulseSideLayout.FEAR_GREED, layout(250, 168))
        // Zweizeilige Schlagzeile braucht für zwei Nebenzeilen mehr Höhe
        assertEquals(PulseSideLayout.FEAR_GREED, layout(250, 204, headlineLines = 2))
        assertEquals(PulseSideLayout.TWO_LINES, layout(250, 205, headlineLines = 2))
        // Marktbreite passt allein nicht in ihre Zeile: nie abgeschnitten, nur Fear & Greed
        assertEquals(PulseSideLayout.FEAR_GREED, layout(250, 200, breadth = 230f))
        // Fear & Greed passt nicht, die Marktbreite schon
        assertEquals(PulseSideLayout.BREADTH, layout(250, 200, fng = 230f, breadth = 130f))
        // Nur ein Teil vorhanden
        assertEquals(PulseSideLayout.BREADTH, layout(250, 200, fng = null))
        assertEquals(PulseSideLayout.FEAR_GREED, layout(250, 200, breadth = null))
        assertNull(layout(250, 200, fng = null, breadth = null))
        // Zu niedrig für jede Nebenzeile (klein, 2×2) oder zu schmal für jeden Teil
        assertNull(layout(250, 167))
        assertNull(layout(140, 220))
        // Unbekannte Grösse: beides in einer Zeile
        assertEquals(PulseSideLayout.ONE_LINE, layout(0, 0))
    }

    @Test
    fun `Marktbreite mit Punkt nur als zweite Zeile gemessen`() {
        // 250 dp: nutzbar 250 − Innenabstand − Sicherheit; die Marktbreite passt ohne Punkt knapp, mit Punkt nicht
        val usable = 250f - PulseWidgetMath.PADDING_DP - WidgetTextFit.SAFETY_DP
        fun layout(height: Int, fng: Float?) = PulseWidgetMath.sideLayout(
            250, height, 1,
            fearGreedWidthDp = fng,
            breadthWidthDp = usable,
            bothWidthDp = fng?.let { it + usable },
            breadthLineWidthDp = usable + 8f,
        )
        // Allein in der ersten Zeile (ohne Punkt): passt
        assertEquals(PulseSideLayout.BREADTH, layout(200, fng = null))
        // Als zweite Zeile (mit Punkt) zu breit: nicht untereinander, nur Fear & Greed
        assertEquals(PulseSideLayout.FEAR_GREED, layout(200, fng = 120f))
        // Ohne eigene Breite der zweiten Zeile gilt die der ersten
        assertEquals(
            PulseSideLayout.TWO_LINES,
            PulseWidgetMath.sideLayout(250, 200, 1, fearGreedWidthDp = 120f, breadthWidthDp = usable, bothWidthDp = 400f),
        )
    }

    @Test
    fun `Reihenfolge der Nebenzeilen wie iOS`() {
        assertEquals(
            listOf(PulseSideLayout.ONE_LINE, PulseSideLayout.TWO_LINES, PulseSideLayout.FEAR_GREED, PulseSideLayout.BREADTH),
            PulseWidgetMath.sideCandidates(hasFearGreed = true, hasBreadth = true, twoLines = true),
        )
        assertEquals(
            listOf(PulseSideLayout.ONE_LINE, PulseSideLayout.FEAR_GREED, PulseSideLayout.BREADTH),
            PulseWidgetMath.sideCandidates(hasFearGreed = true, hasBreadth = true, twoLines = false),
        )
        assertEquals(listOf(PulseSideLayout.FEAR_GREED), PulseWidgetMath.sideCandidates(true, false, true))
        assertEquals(listOf(PulseSideLayout.BREADTH), PulseWidgetMath.sideCandidates(false, true, true))
        assertTrue(PulseWidgetMath.sideCandidates(false, false, true).isEmpty())
        // Höhe für zwei Nebenzeilen: 150 + 2 × 18 dp (zweizeilige Schlagzeile + 19)
        assertFalse(PulseWidgetMath.sideLinesFit(185, headlineLines = 1, sideLines = 2))
        assertTrue(PulseWidgetMath.sideLinesFit(186, headlineLines = 1, sideLines = 2))
        assertTrue(PulseWidgetMath.sideLinesFit(0, headlineLines = 2, sideLines = 2))
        assertFalse(PulseWidgetMath.showsFearGreed(250, 185, headlineLines = 1, textWidthDp = 130f, sideLines = 2))
    }

    @Test
    fun `Zeitraum vor den Chips nimmt ihnen Platz weg`() {
        val widths = listOf(64f, 64f, 64f)
        // «24h» (≈ 18 dp) + 4 dp Abstand
        val leading = PulseWidgetMath.periodLeadingDp(18f)
        assertEquals(22f, leading, 1e-6f)
        assertEquals(0f, PulseWidgetMath.periodLeadingDp(null), 1e-6f)
        assertEquals(0f, PulseWidgetMath.periodLeadingDp(0f), 1e-6f)
        // Ohne Zeitraum passen bei 234 dp drei Coins, mit «24h» davor nur noch zwei — keiner abgeschnitten
        assertEquals(3, PulseWidgetMath.coinCount(234, widths))
        assertEquals(2, PulseWidgetMath.coinCount(234, widths, leading))
        assertEquals(3, PulseWidgetMath.coinCount(256, widths, leading))
        assertEquals(2, PulseWidgetMath.coinCount(255, widths, leading))
        // Mindestens BTC bleibt
        assertEquals(1, PulseWidgetMath.coinCount(100, widths, leading))
    }
}
