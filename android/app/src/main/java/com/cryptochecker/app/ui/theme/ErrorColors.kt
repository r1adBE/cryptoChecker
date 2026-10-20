package com.cryptochecker.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Fehlerfarben aller Akzentfarben, hell und dunkel: warmes Orange-Rot statt Material-Rot
 * (hell #B3261E auf rosa #F9DEDC, dunkel Rosa #FFB4AB — fast wie «fallend»). Gleiche Werte:
 * iOS `ColorTokens.error`. Hoher Kontrast lässt die Fehlerfarbe unverändert (nur primary und
 * Nebentexte werden verstärkt); #B8361A erreicht schon AA auf jeder hellen Fläche.
 *
 * Hell: error #B8361A — 5.58:1 auf Papier #FBF9F6, 4.53:1 auf der dunkelsten Karte #E5E2DB,
 * Weiss darauf 5.86:1; Fehlerfläche #FFDBD0 mit Schrift #4A1C10 (11.1:1).
 * Dunkel: error #FF5833 — 5.9:1 auf #131313, Schwarz darauf; Fehlerfläche #4A1C10 mit Schrift
 * #FFDBD0 (12:1). Die Flächen tragen den Banner «Mitteilungen aus» und die Wisch-Leiste Löschen.
 */

internal val ErrorLight = Color(0xFFB8361A)
internal val ErrorContainerLight = Color(0xFFFFDBD0)
internal val OnErrorContainerLight = Color(0xFF4A1C10)
internal val ErrorDark = Color(0xFFFF5833)

internal fun ColorScheme.withWarmError(dark: Boolean): ColorScheme = if (dark) {
    copy(
        error = ErrorDark,
        onError = Color.Black,
        errorContainer = OnErrorContainerLight,
        onErrorContainer = ErrorContainerLight,
    )
} else {
    copy(
        error = ErrorLight,
        onError = Color.White,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
    )
}
