package com.cryptochecker.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Setzt Erscheinungsbild-Einstellungen um, die ausserhalb von Compose wirken:
 * das App-Icon auf dem Startbildschirm und Hell/Dunkel für Fenster und Dialoge.
 */
@Singleton
class AppearanceApplier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Schaltet das App-Icon um. Jede Farbe ist ein eigener activity-alias im
     * Manifest; aktiv ist genau einer. Erst den neuen einschalten, dann die
     * anderen aus — so gibt es keinen Moment ganz ohne Eintrag im Launcher.
     *
     * Hängt nur an der Farbe (nicht an Hell/Dunkel): Ist der Alias schon aktiv, ändert sich
     * nichts ([setState] prüft den Zustand) — umgeschaltet wird also nur nach einem Farbwechsel.
     * Manche Launcher brauchen ein paar Sekunden, bis das neue Icon erscheint,
     * und entfernen dabei eine Verknüpfung auf dem Startbildschirm.
     */
    fun applyLauncherIcon(accent: AccentColor) {
        val pm = context.packageManager
        val targetAlias = accent.launcherAlias()

        runCatching {
            setState(pm, ComponentName(context.packageName, targetAlias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            AccentColor.allLauncherAliases()
                .filter { it != targetAlias }
                .forEach {
                    setState(pm, ComponentName(context.packageName, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                }
        }.onFailure { Timber.w(it, "App-Icon konnte nicht umgeschaltet werden") }
    }

    private fun setState(pm: PackageManager, component: ComponentName, state: Int) {
        if (pm.getComponentEnabledSetting(component) == state) return
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    /** Hell/Dunkel für alles, was nicht Compose ist (Fensterhintergrund, Systemdialoge). */
    /** null = dem System folgen. Merkt sich die Wahl zusätzlich im kleinen Start-Zwischenspeicher. */
    fun applyNightMode(dark: Boolean?) {
        writeCachedNightMode(dark)
        AppCompatDelegate.setDefaultNightMode(
            when (dark) {
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                true -> AppCompatDelegate.MODE_NIGHT_YES
                false -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    /**
     * Start ohne runBlocking (K-6): Hell/Dunkel liegt zusätzlich in einer winzigen
     * SharedPreferences-Datei, die Android beim ersten Zugriff ohne DataStore liest.
     * [CachedNightMode.Missing] = noch nie geschrieben (Update von altem Build, frische
     * Installation, Systemsicherung ohne diese Datei) – dann liest der Start einmal synchron.
     */
    fun cachedNightMode(): CachedNightMode =
        when (context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).getString(KEY_NIGHT, null)) {
            NIGHT_SYSTEM -> CachedNightMode.Known(null)
            NIGHT_DARK -> CachedNightMode.Known(true)
            NIGHT_LIGHT -> CachedNightMode.Known(false)
            else -> CachedNightMode.Missing
        }

    private fun writeCachedNightMode(dark: Boolean?) {
        val value = when (dark) {
            null -> NIGHT_SYSTEM
            true -> NIGHT_DARK
            false -> NIGHT_LIGHT
        }
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NIGHT, null) != value) prefs.edit { putString(KEY_NIGHT, value) }
    }

    /** Ergebnis des Start-Zwischenspeichers. */
    sealed interface CachedNightMode {
        data class Known(val dark: Boolean?) : CachedNightMode
        data object Missing : CachedNightMode
    }

    private companion object {
        // Nicht in den Sicherungsregeln: fehlt nach einer Wiederherstellung = einmal synchron lesen
        const val CACHE_PREFS = "appearance_start"
        const val KEY_NIGHT = "night_mode"
        const val NIGHT_SYSTEM = "system"
        const val NIGHT_DARK = "dark"
        const val NIGHT_LIGHT = "light"
    }
}
