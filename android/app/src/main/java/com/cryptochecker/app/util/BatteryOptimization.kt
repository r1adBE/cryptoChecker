package com.cryptochecker.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Hintergrund-Aktualisierung und Live-Modus laufen nur zuverlässig, wenn die
 * App von der Akku-Optimierung ausgenommen ist. Android verschiebt sonst
 * Hintergrundarbeit im Doze-Modus teils um Stunden.
 *
 * Play-Store-konform: Die App fragt NICHT direkt per
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (diese Berechtigung erlaubt Google nur
 * für wenige App-Arten, ein Kurs-Checker gehört nicht dazu). Stattdessen
 * öffnet sie die App-Info, wo der Nutzer selbst «Akku → Unbeschränkt» wählt.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val powerManager = context.getSystemService<PowerManager>() ?: return false
        return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Öffnet die App-Info (dort: Akku → Unbeschränkt). Blendet ein Hersteller
     * sie aus, landet der Nutzer in der Liste der Akku-Ausnahmen.
     */
    fun openSettings(context: Context) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (start(context, appDetails)) return

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, fallback)
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }
            .onFailure { Timber.w(it, "Akku-Einstellungen nicht erreichbar") }
            .getOrDefault(false)
}
