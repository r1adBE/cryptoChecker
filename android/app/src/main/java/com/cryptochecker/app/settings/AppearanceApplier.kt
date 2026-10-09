package com.cryptochecker.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Setzt Erscheinungsbild-Einstellungen um, die außerhalb von Compose wirken:
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
     * Manche Launcher brauchen ein paar Sekunden, bis das neue Icon erscheint,
     * und entfernen dabei eine Verknüpfung auf dem Startbildschirm.
     */
    fun applyLauncherIcon(accent: AccentColor, dark: Boolean) {
        val pm = context.packageManager
        val targetAlias = accent.launcherAlias(dark)

        runCatching {
            setState(pm, ComponentName(context.packageName, targetAlias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            AccentColor.entries
                .flatMap { listOf(it.launcherAlias(true), it.launcherAlias(false)) }
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
    /** null = dem System folgen. */
    fun applyNightMode(dark: Boolean?) {
        AppCompatDelegate.setDefaultNightMode(
            when (dark) {
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                true -> AppCompatDelegate.MODE_NIGHT_YES
                false -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }
}
