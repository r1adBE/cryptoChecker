package com.cryptochecker.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme

/**
 * Art einer kurzen Meldung unten (Snackbar, Banner): bestimmt Rahmen und Symbol, nie den Text.
 * [INFO] ist der Standard für alles, was weder Erfolg noch Fehler meldet. Wie iOS `SnackbarKind`.
 */
enum class SnackbarKind { SUCCESS, ERROR, INFO }

/**
 * Farbe von Rahmen (1.5 dp) und Symbol einer Meldung je [SnackbarKind] — reines Kotlin, Werte geteilt mit iOS
 * (`snackbar_colors.json`).
 *
 * - [SnackbarKind.SUCCESS]: Steigend-Farbe des Kursfarben-Schemas, aber nie getauscht ([PriceColors.ok]) — Erfolg
 *   ist keine Kursrichtung, bei «Farben tauschen» (Rot = steigend) bliebe er sonst rot.
 * - [SnackbarKind.ERROR]: Fehlerfarbe der App ([ErrorLight] / [ErrorDark]); bei einem Schema für Farbsehschwäche
 *   ([colorVision]) dessen Fallend-Farbe (Orange statt Rot, ebenfalls nie getauscht).
 * - [SnackbarKind.INFO]: null — kein farbiger Rahmen, kein Symbol.
 *
 * Alle Töne erreichen als Grafik ≥ 3:1 auf `surfaceContainerHighest` (SnackbarColorsTest). Die Bedeutung trägt
 * zusätzlich das Symbol (Häkchen bzw. Ausrufezeichen) und immer der Meldungstext.
 */
object SnackbarPalette {

    /** Schemata für Farbsehschwäche: Erfolg/Fehler nehmen deren Blau/Orange-Töne. */
    fun colorVision(scheme: PriceColorScheme): Boolean =
        scheme == PriceColorScheme.BLUE_ORANGE || scheme == PriceColorScheme.CALM_COLOR_VISION

    /** Ton (ARGB) für [kind]; null bei [SnackbarKind.INFO]. */
    fun tone(
        kind: SnackbarKind,
        scheme: PriceColorScheme,
        dark: Boolean,
        highContrast: Boolean,
        accent: AccentColor?,
    ): Int? = when (kind) {
        SnackbarKind.SUCCESS -> scheme.up(dark, highContrast, inverted = false, accent = accent)
        SnackbarKind.ERROR ->
            if (colorVision(scheme)) scheme.down(dark, highContrast, inverted = false, accent = accent)
            else (if (dark) ErrorDark else ErrorLight).toArgb()
        SnackbarKind.INFO -> null
    }
}
