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

    /**
     * Schrift und Symbole auf gefüllter Akzentfläche (Knöpfe, «+»-Kreis, gewählte gefüllte
     * Chips, Marken in Verläufen): die kontrastsichere `onPrimary` des Farbschemas je Akzentfarbe.
     * Wie iOS `@Environment(\.onAccent)`.
     */
    val onAccent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary

    val positive: Color
        @Composable @ReadOnlyComposable get() = PriceColors.ok

    val negative: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

    val neutral: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val outline: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

    /**
     * Leichte Füllung für «ausgewählt» oder «hervorgehoben» ([alpha] 0.08 … 0.3): hell eine
     * Spur der Akzentfarbe, dunkel neutral grau. Dunkel wirkt eine schwache Akzentfarbe sonst
     * bräunlich (Orange, Rot) statt farbig.
     */
    @Composable @ReadOnlyComposable
    fun accentTint(alpha: Float): Color =
        if (LocalDarkTheme.current) MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.8f)
        else MaterialTheme.colorScheme.primary.copy(alpha = alpha)

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
 * Fünfstufige Skala von «Extrem Bear» bis «Extrem Bull» — feste Farben je Hell/Dunkel (Werte:
 * [MarketScalePalette.FIXED_LIGHT] / [MarketScalePalette.FIXED_DARK], M-8). Jede Stufe erreicht
 * auch als Schrift ≥ 4.5:1 auf den Flächen ihres Modus. Auch der Stufenpunkt der Fear-&-Greed-Karte (wie im
 * Puls-Widget, seit Build 69 statt des Verlaufs aus [MarketScalePalette.steps]).
 */
object MarketScaleColors {
    fun steps(dark: Boolean): List<Color> = MarketScalePalette.fixedSteps(dark).map { Color(it) }

    /** Text auf einer gefüllten Stufe (hell weiss, dunkel fast schwarz). */
    fun onStep(dark: Boolean): Color = Color(MarketScalePalette.fixedOnStep(dark))
}

/** Feste Erkennungsfarben einzelner Coins (Anteilsbalken der Dominanz) — wie iOS `AssetColors`. */
object AssetColors {
    /** Bitcoin-Orange; hell dunkler (#F7931A hielt auf heller Fläche nur ≈ 2.2:1), Werte [AssetPalette]. */
    val bitcoin: Color
        @Composable @ReadOnlyComposable get() = Color(AssetPalette.bitcoin(LocalDarkTheme.current))
    /** Ethereum-Blau; hell dunkler (#627EEA hielt auf #E5E2DB nur ≈ 2.85:1), Werte [AssetPalette]. */
    val ethereum: Color
        @Composable @ReadOnlyComposable get() = Color(AssetPalette.ethereum(LocalDarkTheme.current))
}
