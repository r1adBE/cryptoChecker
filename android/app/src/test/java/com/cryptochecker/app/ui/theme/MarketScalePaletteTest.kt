package com.cryptochecker.app.ui.theme

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** J-15: Stufenfarben der Fear-&-Greed-Skala. Wie iOS `MarketScalePaletteTests`. */
class MarketScalePaletteTest {

    @Test
    fun `Raender sind die Kursfarben, Mitte neutral`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            for (inv in listOf(false, true)) {
                val s = MarketScalePalette.steps(scheme, dark, hc, inv)
                assertEquals(5, s.size)
                assertEquals(scheme.down(dark, hc, inv), s[0])
                assertEquals(scheme.up(dark, hc, inv), s[4])
                assertEquals(if (dark) MarketScalePalette.NEUTRAL_DARK else MarketScalePalette.NEUTRAL_LIGHT, s[2])
            }
        }
    }

    @Test
    fun `Farbsehschwaeche-Schema gilt`() {
        val s = MarketScalePalette.steps(PriceColorScheme.CALM_COLOR_VISION, dark = false)
        assertEquals(0xFF9F4300.toInt(), s[0]) // Orange = Angst
        assertEquals(0xFF1460AB.toInt(), s[4]) // Blau = Gier
        // Mischung 45 % Richtung Grau #707070
        assertEquals(0xFF8A5732.toInt(), s[1])
        assertEquals(0xFF3D6790.toInt(), s[3])
    }

    @Test
    fun `Stufen und Farbe je Wert`() {
        assertEquals(0, MarketScalePalette.level(0))
        assertEquals(0, MarketScalePalette.level(24))
        assertEquals(1, MarketScalePalette.level(25))
        assertEquals(1, MarketScalePalette.level(44))
        assertEquals(2, MarketScalePalette.level(45))
        assertEquals(2, MarketScalePalette.level(55))
        assertEquals(3, MarketScalePalette.level(56))
        assertEquals(3, MarketScalePalette.level(75))
        assertEquals(4, MarketScalePalette.level(76))
        assertEquals(4, MarketScalePalette.level(100))
        val scheme = PriceColorScheme.GREEN_RED
        assertEquals(scheme.up(true), MarketScalePalette.color(90, scheme, dark = true))
        assertEquals(scheme.down(false), MarketScalePalette.color(10, scheme, dark = false))
        // Getauscht: Gier in der Fallend-Farbe des Schemas (Rot = steigend)
        assertEquals(scheme.down(true), MarketScalePalette.color(90, scheme, dark = true, inverted = true))
    }

    /** Nicht-Text-Kontrast ≥ 3:1 auf allen Flächen, für jedes Schema, normal und hoher Kontrast. */
    @Test
    fun `jede Stufe mindestens 3 zu 1`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            // Auch mit den Ersatzfassungen je Akzentfarbe
            for (accent in AccentColor.entries + listOf<AccentColor?>(null)) {
                val steps = MarketScalePalette.steps(scheme, dark, hc, accent = accent)
                for ((i, c) in steps.withIndex()) for (bg in if (dark) DARK else LIGHT) {
                    val r = WidgetContrast.ratio(c, bg)
                    assertTrue(
                        "${scheme.name}/${accent?.name}/dark=$dark/hc=$hc/step $i on %06X: %.2f".format(bg and 0xFFFFFF, r),
                        r >= 3.0
                    )
                }
            }
        }
    }

    private companion object {
        val LIGHT = listOf(
            0xFFFFFFFF.toInt(), 0xFFF9F9F9.toInt(), 0xFFF3F3F3.toInt(), 0xFFEEEEEE.toInt(),
            0xFFE8E8E8.toInt(), 0xFFE2E2E2.toInt(),
            // K-9 warm
            0xFFFBF9F6.toInt(), 0xFFF6F3EE.toInt(), 0xFFF1EEE9.toInt(), 0xFFEBE8E2.toInt(), 0xFFE5E2DB.toInt(),
        )
        val DARK = listOf(
            0xFF0E0E0E.toInt(), 0xFF131313.toInt(), 0xFF1B1B1B.toInt(), 0xFF1F1F1F.toInt(),
            0xFF2A2A2A.toInt(), 0xFF353535.toInt(),
        )
    }
}
