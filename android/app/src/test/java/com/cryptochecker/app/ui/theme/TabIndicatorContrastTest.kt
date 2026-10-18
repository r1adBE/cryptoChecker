package com.cryptochecker.app.ui.theme

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.widget.WidgetColors
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anzeiger (Pille) des gewählten Tabs: volle Akzentfarbe auf der Tableiste (surfaceContainer) muss
 * sich mit mindestens 3:1 abheben (WCAG 1.4.11, Nicht-Text-Kontrast), das Symbol darauf (onPrimary)
 * ebenso. Gilt für jede Akzentfarbe, hell und dunkel; iOS `AppTabBar` nimmt dieselben Farben.
 */
class TabIndicatorContrastTest {

    @Test
    fun `Pille hebt sich mit 3 zu 1 von der Leiste ab`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val pill = WidgetColors.of(accent, dark).accentColor
            val bar = if (dark) BAR_DARK else BAR_LIGHT
            val r = WidgetContrast.ratio(pill, bar)
            assertTrue("${accent.name}/dark=$dark: %.2f".format(r), r >= 3.0)
        }
    }

    @Test
    fun `Symbol auf der Pille mit 3 zu 1`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val colors = WidgetColors.of(accent, dark)
            val r = WidgetContrast.ratio(colors.onAccentColor, colors.accentColor)
            assertTrue("${accent.name}/dark=$dark: %.2f".format(r), r >= 3.0)
        }
    }

    private companion object {
        /** surfaceContainer der App-Farbschemata (AppColorSchemes.kt, iOS `AppColors.container`). */
        val BAR_LIGHT = 0xFFF1EEE9.toInt()
        val BAR_DARK = 0xFF1F1F1F.toInt()
    }
}
