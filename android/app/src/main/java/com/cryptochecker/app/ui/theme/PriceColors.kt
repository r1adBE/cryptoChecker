package com.cryptochecker.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Gewinn und Verlust in festen Farben — unabhängig von der Akzentfarbe,
 * sonst sähen bei Rot oder Orange beide Richtungen rötlich aus.
 * Grün/Rot oder Blau/Orange je nach Einstellung «Kursfarben»
 * ([LocalPriceColorScheme], gesetzt in [CryptoCheckerTheme]); bei hohem Kontrast
 * ([LocalHighContrast]) die kräftigeren Fassungen; [LocalPriceColorsInverted] tauscht
 * die beiden Farben (Rot = steigend). Ist eine Kursfarbe der Akzentfarbe ([LocalAccentColor]) zu
 * ähnlich (ΔE2000 < 24), gilt bei «Ruhig» ihre Ersatzfassung ([PriceColorScheme.up]); die übrigen
 * Schemata bleiben feste Börsenfarben. Farbe nie allein: Änderungen
 * stehen immer mit + / −.
 */
object PriceColors {
    val up: Color
        @Composable @ReadOnlyComposable get() =
            Color(
                LocalPriceColorScheme.current.up(
                    LocalDarkTheme.current, LocalHighContrast.current, LocalPriceColorsInverted.current,
                    LocalAccentColor.current,
                )
            )

    val down: Color
        @Composable @ReadOnlyComposable get() =
            Color(
                LocalPriceColorScheme.current.down(
                    LocalDarkTheme.current, LocalHighContrast.current, LocalPriceColorsInverted.current,
                    LocalAccentColor.current,
                )
            )

    /**
     * «Alles in Ordnung» (z. B. Status der Merkliste): Steigend-Farbe des Schemas,
     * aber nie getauscht — sonst stünde «aktuell» in Rot.
     */
    val ok: Color
        @Composable @ReadOnlyComposable get() =
            Color(
                LocalPriceColorScheme.current.up(
                    LocalDarkTheme.current, LocalHighContrast.current, accent = LocalAccentColor.current
                )
            )

    @Composable
    @ReadOnlyComposable
    fun forChange(change: Double?): Color = if ((change ?: 0.0) >= 0) up else down
}
