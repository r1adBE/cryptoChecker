package com.cryptochecker.app.parity

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemeinsame Testfälle `price_colors.json`: Kursfarben je Schema × Akzent (iOS: `PriceColorsParityTests.swift`). */
class PriceColorsParityTest {

    private val data = ParityJson.load("price_colors.json")

    private fun hex(color: Int) = "%06X".format(color and 0xFFFFFF)

    @Test
    fun cases() {
        @Suppress("UNCHECKED_CAST")
        val cases = data["cases"] as List<Map<String, Any?>>
        assertEquals(PriceColorScheme.entries.size * (AccentColor.entries.size + 1) * 8, cases.size)
        for (c in cases) {
            val scheme = PriceColorScheme.valueOf(c["scheme"] as String)
            val accent = (c["accent"] as String?)?.let(AccentColor::valueOf)
            val dark = c["dark"] as Boolean
            val hc = c["highContrast"] as Boolean
            val inverted = c["inverted"] as Boolean
            val key = "${scheme.name}/${accent?.name}/dark=$dark/hc=$hc/inv=$inverted"
            assertEquals("$key up", c["up"], hex(scheme.up(dark, hc, inverted, accent)))
            assertEquals("$key down", c["down"], hex(scheme.down(dark, hc, inverted, accent)))
        }
    }

    @Test
    fun accents() {
        assertEquals(PriceColorScheme.MIN_ACCENT_DELTA_E, data["minAccentDeltaE"] as Double, 0.0)
        @Suppress("UNCHECKED_CAST")
        val accents = data["accents"] as Map<String, Map<String, Any?>>
        assertEquals(AccentColor.entries.map { it.name }, accents.keys.toList())
        for (accent in AccentColor.entries) {
            assertEquals(accents[accent.name]!!["light"], hex(accent.primary(dark = false)))
            assertEquals(accents[accent.name]!!["dark"], hex(accent.primary(dark = true)))
        }
    }
}
