package com.cryptochecker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Semantische Farben der App (wie `AppColors`/`AppSemantic` in iOS `Theme.swift`).
 * Ansichten nehmen diese Namen statt fester Farbwerte:
 *
 * - [brand] — Akzentfarbe (Knöpfe, Hervorhebungen), aus dem Farbschema
 * - [positive] — «in Ordnung» (nie getauscht), aus [PriceColors.ok]
 * - [negative] — Fehler/Gefahr, `colorScheme.error`
 * - [neutral] / [outline] — Nebentexte bzw. Linien (`onSurfaceVariant` / `outline`)
 * - [warning] / [warningText] — warmes Bernstein für «Achtung», unabhängig von der Akzentfarbe
 *
 * Steigend/fallend kommt immer aus [PriceColors] (Kursfarben, Farbsehschwäche, Tausch).
 */
object AppColors {
    val brand: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    val positive: Color
        @Composable @ReadOnlyComposable get() = PriceColors.ok

    val negative: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

    val neutral: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val outline: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

    /** Bernstein für Symbole und Punkte (⚡, Status «Achtung»). */
    val warning: Color
        @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) WarningDark else WarningLight

    /** Dunkleres Bernstein für Text: AA-Kontrast (≥ 4.5:1) auch auf den hellen Karten. */
    val warningText: Color
        @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) WarningDark else WarningTextLight

    private val WarningDark = Color(0xFFFFC94D)
    private val WarningLight = Color(0xFFB26B00)
    private val WarningTextLight = Color(0xFF8A5300)
}

/**
 * Fünfstufige Skala von «Extrem Bear» bis «Extrem Bull» (Marktzonen, Fear & Greed) —
 * wie `CycleZonePalette` in iOS. Eigene, feste Farben: Die Skala zeigt Stufen, keine
 * Kursrichtung; der Wert steht immer als Zahl bzw. Wort daneben.
 */
object MarketScaleColors {
    val steps: List<Color> = listOf(
        Color(0xFFB42318), // Extrem Bear
        Color(0xFFE5484D), // Bear
        Color(0xFF7A7A7A), // Neutral
        Color(0xFF2FA36B), // Bull
        Color(0xFF0B7A45), // Extrem Bull
    )

    /** Text auf einer Stufe: weiss nur auf den dunklen Randstufen, sonst dunkel (Kontrast). */
    fun onStep(index: Int): Color = if (index == 0 || index == steps.lastIndex) OnDark else OnLight

    private val OnDark = Color.White
    private val OnLight = Color(0xFF111111)
}

/** Feste Erkennungsfarben einzelner Coins (Anteilsbalken der Dominanz) — wie iOS `AssetColors`. */
object AssetColors {
    val bitcoin = Color(0xFFF7931A)
    val ethereum = Color(0xFF627EEA)
}
