package com.cryptochecker.app.widget

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Kontrast für Widget-Farben (reines Kotlin, testbar) — dieselbe Rechnung wie
 * `withContrast` im App-Thema: Bei hohem Kontrast wird die Akzentfarbe schrittweise
 * mit Schwarz (hell) bzw. Weiss (dunkel) gemischt, bis sie 7:1 erreicht.
 */
object WidgetContrast {

    /** Dunkelste Kartenfläche der App (surfaceContainerHighest), Bezug wie im App-Thema. */
    const val LIGHT_REFERENCE = 0xFFE2E2E2.toInt()
    const val DARK_REFERENCE = 0xFF353535.toInt()

    /** Relative Leuchtdichte nach WCAG (sRGB). */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [color] so weit Richtung Schwarz/Weiss mischen, bis der Kontrast zu [background] [target] erreicht. */
    fun withContrast(color: Int, background: Int, dark: Boolean, target: Double = 7.0): Int {
        val toward = if (dark) 255 else 0
        var t = 0.0
        var result = color
        while (ratio(result, background) < target && t < 1.0) {
            t = (t + 0.02).coerceAtMost(1.0)
            result = mix(color, toward, t)
        }
        return result
    }

    /** Akzentfarbe für hohen Kontrast, bezogen auf die Kartenfläche der App. */
    fun highContrastAccent(accent: Int, dark: Boolean): Int =
        withContrast(accent, if (dark) DARK_REFERENCE else LIGHT_REFERENCE, dark)

    /** Deckkraft der Trennlinie im Listen-Widget (normal / hoher Kontrast). */
    const val DIVIDER_ALPHA = 0.25
    const val DIVIDER_ALPHA_HIGH_CONTRAST = 0.45

    /**
     * Trennlinie zwischen den Paaren im Listen-Widget: Akzentfarbe (bei hohem Kontrast die
     * auf 7:1 gebrachte) mit geringer Deckkraft, damit die Liste ruhig bleibt.
     */
    fun dividerColor(accent: Int, dark: Boolean, highContrast: Boolean): Int {
        val color = if (highContrast) highContrastAccent(accent, dark) else accent
        return withAlpha(color, if (highContrast) DIVIDER_ALPHA_HIGH_CONTRAST else DIVIDER_ALPHA)
    }

    /** [color] mit der Deckkraft [alpha] (0–1); die Farbkanäle bleiben. */
    fun withAlpha(color: Int, alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun mix(color: Int, toward: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val c = (color shr shift) and 0xFF
            return (c + (toward - c) * t).roundToInt().coerceIn(0, 255)
        }
        val alpha = (color ushr 24) and 0xFF
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
