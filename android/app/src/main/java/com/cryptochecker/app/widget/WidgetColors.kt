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
) {
    /** Grundfarbe mit der gewünschten Deckkraft (0–100 %). */
    fun colorWithOpacity(opacityPercent: Int): Int {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    // Dieselben Kursfarben wie in der App (die früheren Widget-Grüntöne lagen
    // hell unter 4.5:1). Die Richtung steht zusätzlich immer als + / −.
    val upColor: Int
        get() = priceColors.up(dark, highContrast, inverted)

    val downColor: Int
        get() = priceColors.down(dark, highContrast, inverted)

    /**
     * Trennlinie zwischen den Paaren im Listen-Widget: Akzentfarbe (hell/dunkel wie die
     * übrigen Widget-Farben) mit geringer Deckkraft, bei hohem Kontrast kräftiger.
     */
    val dividerColor: Int
        get() = WidgetContrast.dividerColor(accentColor, dark, highContrast)

    companion object {
        const val DEFAULT_OPACITY = 90

        fun of(
            accent: AccentColor,
            dark: Boolean,
            priceColors: PriceColorScheme = PriceColorScheme.DEFAULT,
            highContrast: Boolean = false,
            inverted: Boolean = false,
        ): WidgetColors = base(accent, dark).copyWith(priceColors, highContrast, inverted)

        // Hoher Kontrast: Nebentexte wie der Haupttext, «0.00%» im bisherigen Nebentext-Grau.
        private fun WidgetColors.copyWith(
            priceColors: PriceColorScheme,
            highContrast: Boolean,
            inverted: Boolean,
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
                baseColor = 0xFFEEEEEE.toInt(),
                textColor = 0xFF1B1B1B.toInt(),
                secondaryTextColor = 0xFF474747.toInt(),
                accentColor = 0xFFB14D29.toInt(),
                onAccentColor = 0xFFFFFFFF.toInt(),
                neutralColor = 0xFF777777.toInt(),
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
                baseColor = 0xFFEEEEEE.toInt(),
                textColor = 0xFF1B1B1B.toInt(),
                secondaryTextColor = 0xFF474747.toInt(),
                accentColor = 0xFFCA2E3C.toInt(),
                onAccentColor = 0xFFFFFFFF.toInt(),
                neutralColor = 0xFF777777.toInt(),
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
                baseColor = 0xFFEEEEEE.toInt(),
                textColor = 0xFF1B1B1B.toInt(),
                secondaryTextColor = 0xFF474747.toInt(),
                accentColor = 0xFF2E66D6.toInt(),
                onAccentColor = 0xFFFFFFFF.toInt(),
                neutralColor = 0xFF777777.toInt(),
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
                baseColor = 0xFFEEEEEE.toInt(),
                textColor = 0xFF1B1B1B.toInt(),
                secondaryTextColor = 0xFF474747.toInt(),
                accentColor = 0xFF117C4D.toInt(),
                onAccentColor = 0xFFFFFFFF.toInt(),
                neutralColor = 0xFF777777.toInt(),
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
                baseColor = 0xFFEEEEEE.toInt(),
                textColor = 0xFF1B1B1B.toInt(),
                secondaryTextColor = 0xFF474747.toInt(),
                accentColor = 0xFF22706B.toInt(),
                onAccentColor = 0xFFFFFFFF.toInt(),
                neutralColor = 0xFF777777.toInt(),
                dark = false,
            )
                else -> error("unerreichbar")
            }
    }
}
