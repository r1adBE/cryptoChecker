package com.cryptochecker.app.ui.theme

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme
import kotlin.math.roundToInt

/**
 * J-15: Farben der fünfstufigen Skala «Extreme Angst» … «Extreme Gier» (Fear & Greed), abgeleitet
 * aus den aktiven Kursfarben — so gilt auch das Schema für Farbsehschwäche, hoher Kontrast und der
 * Tausch. Angst = Fallend-Farbe, Gier = Steigend-Farbe, die mittleren Stufen je zu 45 % mit dem
 * neutralen Grau gemischt. Nur für Flächen (Skala, Markierung), nie für Schrift; jede Stufe hält
 * ≥ 3:1 (Nicht-Text-Kontrast) auf allen Flächen bis #E2E2E2 bzw. #353535 (MarketScalePaletteTest).
 * Reines Kotlin, wie iOS `MarketScalePalette.swift`.
 */
object MarketScalePalette {

    /** Neutrale Mitte: hell dunkler (3.82:1 auf #E2E2E2), dunkel heller (3.65:1 auf #353535). */
    const val NEUTRAL_LIGHT = 0xFF707070.toInt()
    const val NEUTRAL_DARK = 0xFF8C8C8C.toInt()

    /** Anteil Grau in den mittleren Stufen «Angst» / «Gier». */
    const val MILD_MIX = 0.45

    /** Stufen von links (Extreme Angst) nach rechts (Extreme Gier), ARGB. */
    fun steps(
        scheme: PriceColorScheme,
        dark: Boolean,
        highContrast: Boolean = false,
        inverted: Boolean = false,
        // Akzentfarbe: Ersatzfassungen der Kursfarben wie überall
        accent: AccentColor? = null,
    ): List<Int> {
        val fear = scheme.down(dark, highContrast, inverted, accent)
        val greed = scheme.up(dark, highContrast, inverted, accent)
        val neutral = if (dark) NEUTRAL_DARK else NEUTRAL_LIGHT
        return listOf(fear, mix(fear, neutral, MILD_MIX), neutral, mix(greed, neutral, MILD_MIX), greed)
    }

    /** Stufe 0…4 eines Fear-&-Greed-Werts (0–100). */
    fun level(value: Int): Int = when {
        value < 25 -> 0
        value < 45 -> 1
        value <= 55 -> 2
        value <= 75 -> 3
        else -> 4
    }

    /** Stufenfarbe eines Werts (für Markierung/Punkt, nicht für die Zahl). */
    fun color(
        value: Int,
        scheme: PriceColorScheme,
        dark: Boolean,
        highContrast: Boolean = false,
        inverted: Boolean = false,
    ): Int = steps(scheme, dark, highContrast, inverted)[level(value)]

    /**
     * M-8: feste Skala «Extrem Bear» … «Extrem Bull» (Puls-Widget, [MarketScaleColors]) je für Hell
     * und Dunkel. Jede Stufe hält auch als Schrift ≥ 4.5:1 auf allen Flächen (hell bis #E2E2E2,
     * warm bis #E5E2DB; dunkel bis #353535) und damit ≥ 3:1 als Grafik. Mittlere Stufen = Rand
     * zu 45 % mit dem Grau gemischt, damit sie sich vom Rand abheben. Früher eine Skala für beide
     * Modi: #B42318 auf #1F1F1F nur 2.51:1. Wie iOS `ColorTokens.scale*`.
     */
    val FIXED_LIGHT: List<Int> = listOf(
        0xFFB42318.toInt(), 0xFF90403A.toInt(), 0xFF636363.toInt(), 0xFF326B50.toInt(), 0xFF0A7240.toInt(),
    )
    val FIXED_DARK: List<Int> = listOf(
        0xFFFF7A70.toInt(), 0xFFD48B86.toInt(), 0xFFA0A0A0.toInt(), 0xFF6ABE95.toInt(), 0xFF3DD68C.toInt(),
    )

    /** Schrift auf einer gefüllten festen Stufe: hell weiss, dunkel fast schwarz (≥ 4.5:1). */
    const val FIXED_ON_LIGHT_STEP = 0xFFFFFFFF.toInt()
    const val FIXED_ON_DARK_STEP = 0xFF111111.toInt()

    fun fixedSteps(dark: Boolean): List<Int> = if (dark) FIXED_DARK else FIXED_LIGHT

    fun fixedOnStep(dark: Boolean): Int = if (dark) FIXED_ON_DARK_STEP else FIXED_ON_LIGHT_STEP

    /** Mischung im sRGB-Raum, [t] = Anteil von [b]; deckend. */
    internal fun mix(a: Int, b: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/**
 * Erkennungsfarben einzelner Coins als Werte ([AssetColors]), reines Kotlin für Kontrast-Tests.
 * Bitcoin hell #B86200 (≥ 3:1 als Grafik auf allen hellen und warmen Flächen bis #E2E2E2/#E5E2DB),
 * dunkel das Original #F7931A (≥ 5:1 bis #353535). Ethereum hell #4C64D0 (≥ 4:1 bis #E5E2DB; #627EEA
 * nur ≈ 2.85:1), dunkel das Original #627EEA (≥ 3.3:1 bis #353535). Wie iOS `ColorTokens.bitcoin/ethereum`.
 */
object AssetPalette {
    const val BITCOIN_LIGHT = 0xFFB86200.toInt()
    const val BITCOIN_DARK = 0xFFF7931A.toInt()

    fun bitcoin(dark: Boolean): Int = if (dark) BITCOIN_DARK else BITCOIN_LIGHT

    const val ETHEREUM_LIGHT = 0xFF4C64D0.toInt()
    const val ETHEREUM_DARK = 0xFF627EEA.toInt()

    fun ethereum(dark: Boolean): Int = if (dark) ETHEREUM_DARK else ETHEREUM_LIGHT
}
