package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetContrastTest {

    private val accents = listOf(
        0xFFED835E.toInt(), 0xFFA64827.toInt(), 0xFFFF7173.toInt(), 0xFFBE2B38.toInt(),
        0xFF73A3FC.toInt(), 0xFF2B5FC8.toInt(), 0xFF4ABE83.toInt(), 0xFF107348.toInt(), 0xFF5FB8B1.toInt(), 0xFF22706B.toInt(),
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

    /**
     * Build 83: Mit hohem Kontrast heben die Widgets den Akzent wie die App (7:1 zur dunkelsten Kartenfläche);
     * Kurs-Etikett (onAccent auf Akzent) und Widget-Grund bleiben mindestens AA, ohne hohen Kontrast unverändert.
     * Wie `testHighContrastAccentAsAndroidAndUsedByWidgets` (iOS, AccentContrastTests).
     */
    @Test
    fun `widgets raise the accent in high contrast like the app`() {
        for (accent in com.cryptochecker.app.settings.AccentColor.entries) for (dark in listOf(false, true)) {
            val normal = WidgetColors.of(accent, dark)
            val strong = WidgetColors.of(accent, dark, highContrast = true)
            assertEquals(WidgetContrast.highContrastAccent(normal.accentColor, dark), strong.accentColor)
            val reference = if (dark) WidgetContrast.DARK_REFERENCE else WidgetContrast.LIGHT_REFERENCE
            assertTrue("${accent.name}/dark=$dark", WidgetContrast.ratio(strong.accentColor, reference) >= 7.0)
            assertTrue(WidgetContrast.ratio(strong.onAccentColor, strong.accentColor) >= 4.5)
            assertTrue(WidgetContrast.ratio(strong.accentColor, strong.colorWithOpacity(100)) >= 4.5)
            // Ohne hohen Kontrast bleibt der Akzent der App
            assertEquals(normal.accentColor, WidgetColors.of(accent, dark, highContrast = false).accentColor)
        }
    }

    /** «unverändert» hell #5F5F5F statt #777777 (3.87:1) — AA auf dem Widget-Grund, wie iOS. */
    @Test
    fun `neutral grey reaches AA on the widget base`() {
        assertTrue(WidgetContrast.ratio(0xFF777777.toInt(), 0xFFF1EEE9.toInt()) < 4.5)
        assertTrue(WidgetContrast.ratio(0xFF5F5F5F.toInt(), 0xFFF1EEE9.toInt()) >= 4.5)
        assertTrue(WidgetContrast.ratio(0xFF919191.toInt(), 0xFF1F1F1F.toInt()) >= 4.5)
        val source = listOf(
            "src/main/java/com/cryptochecker/app/widget/WidgetColors.kt",
            "app/src/main/java/com/cryptochecker/app/widget/WidgetColors.kt",
        ).map(::File).firstOrNull { it.exists() }?.readText() ?: return
        assertTrue(source.contains("neutralColor = 0xFF5F5F5F.toInt()"))
        assertTrue(!source.contains("neutralColor = 0xFF777777.toInt()"))
    }

    @Test
    fun `divider is the neutral text colour with low alpha, stronger in high contrast`() {
        // Haupttext dunkel/hell (onSurface) — nie die Akzentfarbe
        for (text in listOf(0xFFE2E2E2.toInt(), 0xFF1B1B1B.toInt())) {
            val normal = WidgetContrast.dividerColor(text, highContrast = false)
            assertEquals(text and 0x00FFFFFF, normal and 0x00FFFFFF)
            assertEquals(64, normal ushr 24) // 25 %
            val strong = WidgetContrast.dividerColor(text, highContrast = true)
            assertEquals(text and 0x00FFFFFF, strong and 0x00FFFFFF)
            assertEquals(115, strong ushr 24) // 45 %
        }
    }

    @Test
    fun `already strong colour stays unchanged`() {
        val black = 0xFF000000.toInt()
        assertEquals(black, WidgetContrast.withContrast(black, 0xFFFFFFFF.toInt(), dark = false))
    }
}
