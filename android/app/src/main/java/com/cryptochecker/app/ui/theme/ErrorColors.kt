package com.cryptochecker.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Fehlerfarben aller Akzentfarben, hell und dunkel: warmes Orange-Rot statt Material-Rot
 * (hell #B3261E auf rosa #F9DEDC, dunkel Rosa #FFB4AB — fast wie «fallend»). Gleiche Werte:
 * iOS `ColorTokens.error`. Hoher Kontrast lässt die Fehlerfarbe unverändert (nur primary und
 * Nebentexte werden verstärkt); #B13419 erreicht schon AA auf jeder hellen Fläche.
 *
 * Hell: error #B13419 — 5.92:1 auf Papier #FBF9F6, 4.81:1 auf der dunkelsten Karte #E5E2DB,
 * Weiss darauf 6.22:1, auf #FFDBD0 4.83:1 und auf der iOS-Fehlerfläche (error · 0.12 über #F1EEE9) 4.5:1;
 * Fehlerfläche #FFDBD0 mit Schrift #4A1C10 (11.1:1).
 * Dunkel: error #FF5833 — 5.9:1 auf #131313, Schwarz darauf; Fehlerfläche #4A1C10 mit Schrift
 * #FFDBD0 (12:1). Die Flächen tragen den Banner «Mitteilungen aus» und die Wisch-Leiste Löschen.
 */

internal val ErrorLight = Color(0xFFB13419)
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
