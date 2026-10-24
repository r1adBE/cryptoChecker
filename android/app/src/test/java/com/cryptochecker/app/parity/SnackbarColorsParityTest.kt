package com.cryptochecker.app.parity

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.SnackbarPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gemeinsame Testfälle `snackbar_colors.json`: Ton von Rahmen und Symbol der Meldungen je Art × Schema × Akzent
 * (iOS: `SnackbarColorsParityTests.swift`).
 */
class SnackbarColorsParityTest {

    private val data = ParityJson.load("snackbar_colors.json")

    private fun hex(color: Int?) = color?.let { "%06X".format(it and 0xFFFFFF) }

    @Test
    fun cases() {
        @Suppress("UNCHECKED_CAST")
        val cases = data["cases"] as List<Map<String, Any?>>
        val expected = SnackbarKind.entries.size * PriceColorScheme.entries.size * (AccentColor.entries.size + 1) * 4
        assertEquals(expected, cases.size)
        for (c in cases) {
            val kind = SnackbarKind.valueOf(c["kind"] as String)
            val scheme = PriceColorScheme.valueOf(c["scheme"] as String)
            val accent = (c["accent"] as String?)?.let(AccentColor::valueOf)
            val dark = c["dark"] as Boolean
            val hc = c["highContrast"] as Boolean
            val key = "${kind.name}/${scheme.name}/${accent?.name}/dark=$dark/hc=$hc"
            assertEquals(key, c["tone"], hex(SnackbarPalette.tone(kind, scheme, dark, hc, accent)))
        }
    }

    @Test
    fun colorVisionSchemes() {
        @Suppress("UNCHECKED_CAST")
        val names = data["colorVisionSchemes"] as List<String>
        assertEquals(names, PriceColorScheme.entries.filter(SnackbarPalette::colorVision).map { it.name }.sorted())
    }
}
