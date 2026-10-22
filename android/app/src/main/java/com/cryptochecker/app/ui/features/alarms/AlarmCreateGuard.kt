package com.cryptochecker.app.ui.features.alarms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ein neuer Alarm zur Zeit: Ein zweiter Tipp auf «Speichern» bzw. eine Vorlage, während das erste Anlegen noch läuft,
 * legte sonst einen zweiten, gleichen Alarm an (zwei INSERTs mit id 0). Seit Build 98; wie `AlarmCreateGuard` (iOS).
 * Der Alarm-Editor sperrt auch beim Bearbeiten (feste id): sonst doppelte Haptik und Bestätigung (IOS6-01).
 */
class AlarmCreateGuard {
    private val running = AtomicBoolean(false)

    /**
     * Startet [block] in [scope]; mit [guarded] nur, wenn kein anderes Anlegen läuft — sonst nichts (null).
     * Frei wird der Wächter, sobald der Job endet (auch bei Abbruch oder Fehler, auch ohne je gestartet zu sein).
     */
    fun launch(scope: CoroutineScope, guarded: Boolean, block: suspend CoroutineScope.() -> Unit): Job? {
        if (guarded && !running.compareAndSet(false, true)) return null
        val job = scope.launch(block = block)
        if (guarded) job.invokeOnCompletion { running.set(false) }
        return job
    }

    /** Läuft gerade ein Anlegen? */
    val busy: Boolean get() = running.get()
}
