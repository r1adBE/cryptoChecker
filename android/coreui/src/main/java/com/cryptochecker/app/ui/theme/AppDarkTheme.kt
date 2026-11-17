package com.cryptochecker.app.ui.theme

import android.view.ViewTreeObserver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.settings.ThemeSchedule
import kotlinx.coroutines.delay

/**
 * Hell/Dunkel der App aus den Einstellungen: «Nach Uhrzeit» ([ThemeSchedule]), fest Hell/Dunkel oder wie das System.
 * Mit «Nach Uhrzeit» wechselt das Bild zur eingestellten Zeit von selbst; zusätzlich wird geprüft, sobald das Fenster
 * wieder den Fokus bekommt (Wartezeiten zählen im Tiefschlaf des Geräts nicht mit).
 */
@Composable
fun rememberAppDark(settings: AppSettings): Boolean {
    val systemDark = isSystemInDarkTheme()
    return ThemeSchedule.forcedDark(settings, rememberThemeClock(settings)) ?: systemDark
}

/** Uhrzeit (ms) für [ThemeSchedule]: neu beim nächsten Wechsel und bei Fokus des Fensters; ohne Zeitplan fest. */
@Composable
fun rememberThemeClock(settings: AppSettings): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scheduled = settings.themeScheduled
    if (scheduled) {
        val view = LocalView.current
        DisposableEffect(view) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused) now = System.currentTimeMillis()
            }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
    }
    LaunchedEffect(scheduled, settings.themeDarkFrom, settings.themeLightFrom, now) {
        if (!scheduled) return@LaunchedEffect
        val current = System.currentTimeMillis()
        val next = ThemeSchedule.nextSwitchAt(settings, current) ?: return@LaunchedEffect
        // Eine Sekunde nach dem Wechsel: die Minute des Wechsels ist sicher erreicht
        delay(next - current + 1_000)
        now = System.currentTimeMillis()
    }
    return now
}
