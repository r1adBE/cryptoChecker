package com.cryptochecker.app.util

import android.os.Process
import android.os.SystemClock
import com.cryptochecker.app.domain.refresh.AppStartTiming

/**
 * Misst App-Start → erstes Bild der Merkliste (aus dem Zwischenspeicher): kalt ab Prozessstart,
 * sonst ab dem Aufbau der Activity. Nur lokal (Bericht «Ablauf»), keine Telemetrie.
 */
object StartupClock {
    @Volatile private var uiCreatedAt = 0L
    @Volatile private var pending = false

    /** MainActivity.onCreate ohne gespeicherten Zustand (kein Drehen). */
    fun onUiCreated() {
        uiCreatedAt = SystemClock.uptimeMillis()
        pending = true
    }

    /** Einmal je Start: Dauer bis jetzt; null = schon gemeldet oder unplausibel. */
    fun onFirstFrame(): Long? {
        if (!pending) return null
        pending = false
        return AppStartTiming.elapsed(Process.getStartUptimeMillis(), uiCreatedAt, SystemClock.uptimeMillis())
    }
}
