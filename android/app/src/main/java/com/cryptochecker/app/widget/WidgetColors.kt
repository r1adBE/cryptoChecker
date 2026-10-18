package com.cryptochecker.app.widget

import android.graphics.Color
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme

/**
 * Farben eines Widgets. Kommen aus der App-Einstellung (Hell/Dunkel,
 * Akzentfarbe und Kursfarben), damit Widget und App gleich aussehen. Pro Widget wählbar
 * bleibt nur die Deckkraft.
 * Werte entsprechen den Flächen- und Textfarben des App-Farbschemas.
 */
class WidgetColors(
    private val baseColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    /** Für Titel und Aktualisieren-Knopf. */
    val accentColor: Int,
    /** Text auf der Akzentfarbe (onPrimary des App-Farbschemas), z. B. im Kurs-Etikett des Charts. */
    val onAccentColor: Int,
    /** Unverändert: gedeckt, damit es sich von Grün und Rot klar absetzt. */
    val neutralColor: Int,
    private val dark: Boolean,
    /** Kursfarben aus der Einstellung «Kursfarben». */
    private val priceColors: PriceColorScheme = PriceColorScheme.DEFAULT,
    /** Hoher Kontrast (Einstellung oder System): kräftigere Kursfarben. */
    private val highContrast: Boolean = false,
    /** Kursfarben getauscht (Rot = steigend); Vorzeichen bleiben. */
    private val inverted: Boolean = false,
    /** Akzentfarbe der App: Kursfarben, die ihr zu ähnlich sind, nehmen ihre Ersatzfassung ([PriceColorScheme.up]). */
    private val accent: AccentColor? = null,
) {
    /** Grundfarbe mit der gewünschten Deckkraft (0–100 %). */
    fun colorWithOpacity(opacityPercent: Int): Int {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    // Dieselben Kursfarben wie in der App (die früheren Widget-Grüntöne lagen
    // hell unter 4.5:1). Die Richtung steht zusätzlich immer als + / −.
    val upColor: Int
        get() = priceColors.up(dark, highContrast, inverted, accent)

    val downColor: Int
        get() = priceColors.down(dark, highContrast, inverted, accent)

    /**
     * Trennlinie zwischen den Paaren im Listen-Widget: neutrales Grau (Haupttext-Farbe mit
     * geringer Deckkraft) statt Akzentfarbe, bei hohem Kontrast kräftiger.
     */
    val dividerColor: Int
        get() = WidgetContrast.dividerColor(textColor, highContrast)

    /**
     * Grund hinter Coin-Logos: dunkel ein heller Kreis (Haupttext-Farbe, leicht gedämpft), damit
     * dunkle Logos sichtbar bleiben; hell ein zarter grauer Kreis.
     */
    val logoBackingColor: Int
        get() = if (dark) Color.argb(235, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        else Color.argb(28, Color.red(secondaryTextColor), Color.green(secondaryTextColor), Color.blue(secondaryTextColor))

    companion object {
        const val DEFAULT_OPACITY = 90

        /** Kleinste wählbare Deckkraft; ältere, tiefere Werte werden beim Lesen angehoben ([WidgetPrefs.getOpacity]). */
        const val MIN_OPACITY = 30

        fun of(
            accent: AccentColor,
            dark: Boolean,
            priceColors: PriceColorScheme = PriceColorScheme.DEFAULT,
            highContrast: Boolean = false,
            inverted: Boolean = false,
        ): WidgetColors = base(accent, dark).copyWith(priceColors, highContrast, inverted, accent)

        // Hoher Kontrast: Nebentexte wie der Haupttext, «0.00%» im bisherigen Nebentext-Grau.
        private fun WidgetColors.copyWith(
            priceColors: PriceColorScheme,
            highContrast: Boolean,
            inverted: Boolean,
            accent: AccentColor,
        ): WidgetColors =
            WidgetColors(
                baseColor = baseColor,
                textColor = textColor,
                secondaryTextColor = if (highContrast) textColor else secondaryTextColor,
                accentColor = accentColor,
                onAccentColor = onAccentColor,
                neutralColor = if (highContrast) secondaryTextColor else neutralColor,
                dark = dark,
                priceColors = priceColors,
                highContrast = highContrast,
                inverted = inverted,
                accent = accent,
            )

        private fun base(accent: AccentColor, dark: Boolean): WidgetColors =
            when (accent to dark) {
                AccentColor.ORANGE to true -> WidgetColors(
                    baseColor = 0xFF1F1F1F.toInt(),
                    textColor = 0xFFE2E2E2.toInt(),
                    secondaryTextColor = 0xFFC6C6C6.toInt(),
                    accentColor = 0xFFED835E.toInt(),
                    onAccentColor = 0xFF410D00.toInt(),
                    neutralColor = 0xFF919191.toInt(),
                    dark = true,
                )
                AccentColor.ORANGE to false -> WidgetColors(
                    baseColor = 0xFFF1EEE9.toInt(),
                    textColor = 0xFF1B1B1B.toInt(),
                    secondaryTextColor = 0xFF474747.toInt(),
                    accentColor = 0xFFA64827.toInt(),
                    onAccentColor = 0xFFFFFFFF.toInt(),
                    neutralColor = 0xFF5F5F5F.toInt(),
                    dark = false,
                )
                AccentColor.RED to true -> WidgetColors(
                    baseColor = 0xFF1F1F1F.toInt(),
                    textColor = 0xFFE2E2E2.toInt(),
                    secondaryTextColor = 0xFFC6C6C6.toInt(),
                    accentColor = 0xFFFF7173.toInt(),
                    onAccentColor = 0xFF480008.toInt(),
                    neutralColor = 0xFF919191.toInt(),
                    dark = true,
                )
                AccentColor.RED to false -> WidgetColors(
                    baseColor = 0xFFF1EEE9.toInt(),
                    textColor = 0xFF1B1B1B.toInt(),
                    secondaryTextColor = 0xFF474747.toInt(),
                    accentColor = 0xFFBE2B38.toInt(),
                    onAccentColor = 0xFFFFFFFF.toInt(),
                    neutralColor = 0xFF5F5F5F.toInt(),
                    dark = false,
                )
                AccentColor.BLUE to true -> WidgetColors(
                    baseColor = 0xFF1F1F1F.toInt(),
                    textColor = 0xFFE2E2E2.toInt(),
                    secondaryTextColor = 0xFFC6C6C6.toInt(),
                    accentColor = 0xFF73A3FC.toInt(),
                    onAccentColor = 0xFF001A57.toInt(),
                    neutralColor = 0xFF919191.toInt(),
                    dark = true,
                )
                AccentColor.BLUE to false -> WidgetColors(
                    baseColor = 0xFFF1EEE9.toInt(),
                    textColor = 0xFF1B1B1B.toInt(),
                    secondaryTextColor = 0xFF474747.toInt(),
                    accentColor = 0xFF2B5FC8.toInt(),
                    onAccentColor = 0xFFFFFFFF.toInt(),
                    neutralColor = 0xFF5F5F5F.toInt(),
                    dark = false,
                )
                AccentColor.GREEN to true -> WidgetColors(
                    baseColor = 0xFF1F1F1F.toInt(),
                    textColor = 0xFFE2E2E2.toInt(),
                    secondaryTextColor = 0xFFC6C6C6.toInt(),
                    accentColor = 0xFF4ABE83.toInt(),
                    onAccentColor = 0xFF002A16.toInt(),
                    neutralColor = 0xFF919191.toInt(),
                    dark = true,
                )
                AccentColor.GREEN to false -> WidgetColors(
                    baseColor = 0xFFF1EEE9.toInt(),
                    textColor = 0xFF1B1B1B.toInt(),
                    secondaryTextColor = 0xFF474747.toInt(),
                    accentColor = 0xFF107348.toInt(),
                    onAccentColor = 0xFFFFFFFF.toInt(),
                    neutralColor = 0xFF5F5F5F.toInt(),
                    dark = false,
                )
                AccentColor.MARRS_GREEN to true -> WidgetColors(
                    baseColor = 0xFF1F1F1F.toInt(),
                    textColor = 0xFFE2E2E2.toInt(),
                    secondaryTextColor = 0xFFC6C6C6.toInt(),
                    accentColor = 0xFF5FB8B1.toInt(),
                    onAccentColor = 0xFF072826.toInt(),
                    neutralColor = 0xFF919191.toInt(),
                    dark = true,
                )
                AccentColor.MARRS_GREEN to false -> WidgetColors(
                    baseColor = 0xFFF1EEE9.toInt(),
                    textColor = 0xFF1B1B1B.toInt(),
                    secondaryTextColor = 0xFF474747.toInt(),
                    accentColor = 0xFF22706B.toInt(),
                    onAccentColor = 0xFFFFFFFF.toInt(),
                    neutralColor = 0xFF5F5F5F.toInt(),
                    dark = false,
                )
                    else -> error("unerreichbar")
            }
    }
}
