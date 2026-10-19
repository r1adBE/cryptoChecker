package com.cryptochecker.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cryptochecker.app.domain.alarm.OemBattery
import timber.log.Timber

/**
 * Öffnet die Akku-/Autostart-Seite des Herstellers ([OemBattery.pages]). Ohne neue Berechtigung und ohne Abfrage der
 * installierten Apps: jede Seite wird explizit gestartet; fehlt sie (ActivityNotFoundException) oder ist sie gesperrt
 * (SecurityException), folgt die nächste und am Ende die App-Info.
 */
object OemBatterySettings {

    /** Hersteller dieses Geräts; null = kein bekanntes Akkusparen. */
    fun vendor(): OemBattery.Vendor? = OemBattery.vendor(Build.MANUFACTURER)

    fun open(context: Context, vendor: OemBattery.Vendor? = vendor()) {
        val opened = vendor?.let { OemBattery.pages(it) }.orEmpty().any { page ->
            val intent = Intent()
                .setComponent(ComponentName(page.packageName, page.className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent); true }
                .onFailure { Timber.i("Herstellerseite fehlt: %s", page.className) }
                .getOrDefault(false)
        }
        if (!opened) AlarmReadinessProbe.openAppDetails(context)
    }
}
