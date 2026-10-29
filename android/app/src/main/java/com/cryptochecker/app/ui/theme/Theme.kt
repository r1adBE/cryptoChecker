package com.cryptochecker.app.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.cryptochecker.app.ui.components.LocalReduceMotion
import com.cryptochecker.app.ui.components.observeReduceMotion

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
 * Hoher Kontrast: Nebentexte und Ränder so kräftig wie der Haupttext; die Akzentfarbe so weit
 * abgedunkelt (hell) bzw. aufgehellt (dunkel), dass sie selbst auf der dunkelsten Kartenfläche
 * mindestens 7:1 erreicht. Im Dunkeln steht sie zudem als Schrift auf der grauen Auswahlfläche.
 */
private fun ColorScheme.withHighContrast(dark: Boolean): ColorScheme {
    val strongText = onSurface
    return copy(
        primary = withContrast(primary, surfaceContainerHighest, dark),
        onSecondaryContainer = if (!dark) onSecondaryContainer else withContrast(primary, DarkSelectedContainer, true),
        onSurfaceVariant = strongText,
        outline = strongText,
        outlineVariant = strongText.copy(alpha = 0.6f),
    )
}

/**
 * Statusleiste passend zur Fläche: Farbe nur vor Android 15 (danach Edge-to-Edge, das System
 * übergeht sie), Symbole dunkel auf hellem Grund und umgekehrt. Nicht in der Vorschau.
 */
@Composable
private fun StatusBarAppearance(surface: Color, dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = surface.toArgb()
        }
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
}

/**
 * Thema der App. Akzentfarbe und Hell/Dunkel stammen aus den Einstellungen; die Farben des
 * Hintergrundbilds (Material You) bleiben bewusst ungenutzt, damit App, Widget und Icon gleich aussehen.
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
    val scheme = appColorScheme(accent, dark).let { if (highContrast) it.withHighContrast(dark) else it }

    StatusBarAppearance(scheme.surface, dark)

    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalDarkTheme provides dark,
        LocalPriceColorScheme provides priceColors,
        LocalHighContrast provides highContrast,
        LocalPriceColorsInverted provides priceColorsInverted,
        // Einmal für den ganzen Baum statt je Zahl einer Zeile
        LocalReduceMotion provides observeReduceMotion(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = appTypography, shapes = appShapes, content = content)
    }
}
