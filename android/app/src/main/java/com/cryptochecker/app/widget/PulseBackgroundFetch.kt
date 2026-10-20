package com.cryptochecker.app.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Losgelöster Abruf des Widgets «Was gerade auffällt» ([PulseWidgetRenderer.updateWithOthers]): Die
 * Kurs-Aktualisierung wartet nicht auf das Netz. Ein Hintergrundlauf (WorkManager, Wecker) wartet ihn am Ende
 * begrenzt ab ([await]) — sonst beendet Android den Prozess mitten im Abruf, und wer nur das Pulse-Widget hat,
 * sähe bloss den Zwischenspeicher.
 */
internal class PulseBackgroundFetch(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile
    private var job: Job? = null

    fun launch(block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    /** Laufenden Abruf höchstens [timeoutMillis] abwarten. @return true = keiner offen bzw. fertig geworden */
    suspend fun await(timeoutMillis: Long): Boolean {
        val current = job?.takeIf { it.isActive } ?: return true
        if (timeoutMillis <= 0L) return false
        return withTimeoutOrNull(timeoutMillis) { current.join() } != null
    }
}
