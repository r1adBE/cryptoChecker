package com.cryptochecker.app.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.PriceColorScheme

/** Aktuelle Akzentfarbe, z. B. für das passende Logo. */
val LocalAccentColor = staticCompositionLocalOf { AccentColor.DEFAULT }

/** true im dunklen Modus — für die passende Logo-Fassung. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** Kursfarben steigend/fallend (Grün/Rot oder Blau/Orange), siehe [PriceColors]. */
val LocalPriceColorScheme = staticCompositionLocalOf { PriceColorScheme.DEFAULT }

/** Hoher Kontrast wirksam (Einstellung oder System), siehe [PriceColors] und [HighContrast]. */
val LocalHighContrast = staticCompositionLocalOf { false }

/** Kursfarben getauscht (Rot = steigend), siehe [PriceColors]. */
val LocalPriceColorsInverted = staticCompositionLocalOf { false }

/**
 * Hoher Kontrast = Einstellung an ODER das System verlangt mehr Kontrast.
 * Ab Android 14 wird ein Wechsel des System-Kontrasts sofort übernommen.
 */
@Composable
fun rememberHighContrast(setting: Boolean): Boolean {
    val context = LocalContext.current
    var system by remember { mutableStateOf(HighContrast.systemRequestsMore(context)) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        DisposableEffect(context) {
            val manager = context.getSystemService(UiModeManager::class.java)
            val listener = UiModeManager.ContrastChangeListener { system = HighContrast.systemRequestsMore(context) }
            manager?.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
            onDispose { manager?.removeContrastChangeListener(listener) }
        }
    }
    return setting || system
}

/** WCAG-Kontrast zweier Farben. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Mischt [color] schrittweise mit Schwarz (hell) bzw. Weiss (dunkel), bis der
 * Kontrast zu [background] mindestens [target] beträgt. Farbton bleibt erhalten.
 */
internal fun withContrast(color: Color, background: Color, dark: Boolean, target: Double = 7.0): Color {
    val toward = if (dark) Color.White else Color.Black
    var t = 0f
    var result = color
    while (contrastRatio(result, background) < target && t < 1f) {
        t = (t + 0.02f).coerceAtMost(1f)
        // Mischung im sRGB-Raum (wie iOS), Farbton bleibt erhalten
        result = Color(
            red = color.red + (toward.red - color.red) * t,
            green = color.green + (toward.green - color.green) * t,
            blue = color.blue + (toward.blue - color.blue) * t,
            alpha = color.alpha,
        )
    }
    return result
}

/**
 * Thema der App. Farbe und Hell/Dunkel kommen aus den Einstellungen; die
 * Farben des Hintergrundbilds (Material You) werden bewusst nicht verwendet,
 * damit App, Widget und Icon einheitlich bleiben.
 */
@Composable
fun CryptoCheckerTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.DEFAULT,
    priceColors: PriceColorScheme = PriceColorScheme.DEFAULT,
    /** Wirksamer hoher Kontrast, siehe [rememberHighContrast]. */
    highContrast: Boolean = false,
    /** Kursfarben tauschen (nur die Farben, nie Vorzeichen). */
    priceColorsInverted: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseScheme = appColorScheme(accent, dark)
    // Hoher Kontrast: Nebentexte und Ränder so kräftig wie der Haupttext,
    // Akzentfarbe so weit abgedunkelt (hell) bzw. aufgehellt (dunkel), dass sie
    // auch auf der dunkelsten Kartenfläche mindestens 7:1 erreicht.
    val colorScheme = if (highContrast) {
        baseScheme.copy(
            primary = withContrast(baseScheme.primary, baseScheme.surfaceContainerHighest, dark),
            onSurfaceVariant = baseScheme.onSurface,
            outline = baseScheme.onSurface,
            outlineVariant = baseScheme.onSurface.copy(alpha = 0.6f),
        )
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Ab Android 15 ignoriert das System die Farbe ohnehin (Edge-to-Edge);
            // für ältere Versionen passend zur Fläche.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.surface.toArgb()
            }
            // Helle Fläche → dunkle Symbole in der Statusleiste und umgekehrt.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalDarkTheme provides dark,
        LocalPriceColorScheme provides priceColors,
        LocalHighContrast provides highContrast,
        LocalPriceColorsInverted provides priceColorsInverted,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography,
            shapes = appShapes,
            content = content
        )
    }
}
