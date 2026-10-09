package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG-Kontrast der Kursfarben: normal ≥ 4.5:1 (AA), hoher Kontrast ≥ 7:1 (AAA).
 * Gegen #FFFFFF/#F9F9F9/#EEEEEE/#E4E4E4 (hell) und #121212/#131313/#1F1F1F (dunkel)
 * sowie in der Pille (14 % Kursfarbe über der Karte #EEEEEE bzw. #1F1F1F).
 * #EEEEEE/#1F1F1F sind auch die Grundfarben der Widgets (WidgetColors).
 */
class PriceColorSchemeTest {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun required(highContrast: Boolean) = if (highContrast) 7.0 else 4.5

    private data class Case(val scheme: PriceColorScheme, val highContrast: Boolean, val up: Boolean, val dark: Boolean) {
        val color: Int get() = if (up) scheme.up(dark, highContrast) else scheme.down(dark, highContrast)
        fun key(background: Int) =
            "${scheme.name}/${if (highContrast) "hc" else "normal"}/${if (up) "up" else "down"}/" +
                "%06X".format(background and 0xFFFFFF)
    }

    private val cases = PriceColorScheme.entries.flatMap { scheme ->
        listOf(false, true).flatMap { hc ->
            listOf(true, false).flatMap { up -> listOf(false, true).map { dark -> Case(scheme, hc, up, dark) } }
        }
    }

    @Test
    fun `Werte wie in der Spezifikation`() {
        assertEquals(0xFF0A6D3E.toInt(), PriceColorScheme.GREEN_RED.up(dark = false))
        assertEquals(0xFF9F4300.toInt(), PriceColorScheme.BLUE_ORANGE.down(dark = false))
        assertEquals(0xFF7CF2B8.toInt(), PriceColorScheme.GREEN_RED.up(dark = true, highContrast = true))
        assertEquals(0xFFFFB0B0.toInt(), PriceColorScheme.GREEN_RED.down(dark = true, highContrast = true))
        assertEquals(0xFF6C2E00.toInt(), PriceColorScheme.BLUE_ORANGE.down(dark = false, highContrast = true))
        // Ohne Angabe: normale Werte, nicht getauscht
        assertEquals(PriceColorScheme.GREEN_RED.up(true, false, false), PriceColorScheme.GREEN_RED.up(true))
    }

    @Test
    fun `Tauschen vertauscht nur die Farben`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            assertEquals(scheme.down(dark, hc), scheme.up(dark, hc, inverted = true))
            assertEquals(scheme.up(dark, hc), scheme.down(dark, hc, inverted = true))
        }
    }

    /** Alle hellen bzw. dunklen Flächen und die getönte Pille: streng, ohne Ausnahmen. */
    @Test
    fun `Kontrast auf allen Flaechen und in der Pille`() {
        for (c in cases) {
            val backgrounds = (if (c.dark) DARK_SURFACES else LIGHT_SURFACES) +
                pill(c.color, if (c.dark) DARK_CARD else LIGHT_CARD)
            for (bg in backgrounds) {
                val ratio = contrast(c.color, bg)
                assertTrue("${c.key(bg)}: %.2f".format(ratio), ratio >= required(c.highContrast))
            }
        }
    }

    /** Pille: Kursfarbe mit 14 % Deckkraft über der Karte (wie ChangePill/PlPill). */
    private fun pill(color: Int, card: Int): Int {
        fun mix(shift: Int): Int {
            val f = (color shr shift) and 0xFF
            val b = (card shr shift) and 0xFF
            return Math.round(PILL_ALPHA * f + (1 - PILL_ALPHA) * b).toInt()
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    @Test
    fun `fromName faellt auf den Standard zurueck`() {
        assertEquals(PriceColorScheme.BLUE_ORANGE, PriceColorScheme.fromName("BLUE_ORANGE"))
        assertEquals(PriceColorScheme.DEFAULT, PriceColorScheme.fromName(null))
        assertEquals(PriceColorScheme.DEFAULT, PriceColorScheme.fromName("BLUE_YELLOW"))
    }

    private companion object {
        const val PILL_ALPHA = 0.14

        /** Weiss, surface, surfaceContainer (Karten, Widgets), surfaceContainerHighest. */
        val LIGHT_SURFACES = listOf(0xFFFFFFFF.toInt(), 0xFFF9F9F9.toInt(), 0xFFEEEEEE.toInt(), 0xFFE4E4E4.toInt())
        val DARK_SURFACES = listOf(0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF1F1F1F.toInt())

        const val LIGHT_CARD = 0xFFEEEEEE.toInt()
        const val DARK_CARD = 0xFF1F1F1F.toInt()
    }
}
