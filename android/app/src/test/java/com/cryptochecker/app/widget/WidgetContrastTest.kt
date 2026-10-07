package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContrastTest {

    private val accents = listOf(
        0xFFED835E.toInt(), 0xFFB14D29.toInt(), 0xFFFF7173.toInt(), 0xFFCA2E3C.toInt(),
        0xFF73A3FC.toInt(), 0xFF2E66D6.toInt(), 0xFF4ABE83.toInt(), 0xFF117C4D.toInt(),
    )

    @Test
    fun `black on white is 21 to 1`() {
        assertEquals(21.0, WidgetContrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test
    fun `high contrast accent reaches 7 to 1 in light and dark`() {
        for (accent in accents) {
            val light = WidgetContrast.highContrastAccent(accent, dark = false)
            val dark = WidgetContrast.highContrastAccent(accent, dark = true)
            assertTrue(WidgetContrast.ratio(light, WidgetContrast.LIGHT_REFERENCE) >= 7.0)
            assertTrue(WidgetContrast.ratio(dark, WidgetContrast.DARK_REFERENCE) >= 7.0)
            // Deckkraft bleibt
            assertEquals(0xFF, light ushr 24)
        }
    }

    @Test
    fun `divider is the accent with low alpha, stronger in high contrast`() {
        for (accent in accents) {
            for (dark in listOf(false, true)) {
                val normal = WidgetContrast.dividerColor(accent, dark, highContrast = false)
                assertEquals(accent and 0x00FFFFFF, normal and 0x00FFFFFF)
                assertEquals(64, normal ushr 24) // 25 %
                val strong = WidgetContrast.dividerColor(accent, dark, highContrast = true)
                assertEquals(WidgetContrast.highContrastAccent(accent, dark) and 0x00FFFFFF, strong and 0x00FFFFFF)
                assertEquals(115, strong ushr 24) // 45 %
            }
        }
    }

    @Test
    fun `already strong colour stays unchanged`() {
        val black = 0xFF000000.toInt()
        assertEquals(black, WidgetContrast.withContrast(black, 0xFFFFFFFF.toInt(), dark = false))
    }
}
