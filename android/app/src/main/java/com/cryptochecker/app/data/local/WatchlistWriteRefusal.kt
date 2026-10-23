package com.cryptochecker.app.data.local

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Schreibweg des Nutzers in die Merkliste abgelehnt ([WatchlistStorageLock.blocksWrites]): Die Oberfläche
 * zeigt dann einen kurzen Hinweis mit Warn-Haptik (`WatchlistLockedHint`). Ausgelöst zentral von
 * [com.cryptochecker.app.data.WatchRepository], nicht von jedem Bildschirm. Mehrere Ablehnungen kurz hintereinander
 * (z. B. eine Start-Auswahl mit mehreren Paaren) ergeben nur einen Hinweis ([shouldNotify]).
 */
object WatchlistWriteRefusal {

    /** Mindestabstand zwischen zwei Hinweisen (ms). */
    const val MIN_GAP_MILLIS = 2_000L

    private val refused = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Je Ablehnung (gedrosselt) ein Ereignis; ohne Beobachter geht es verloren (kein Bildschirm, kein Hinweis). */
    val events: SharedFlow<Unit> = refused.asSharedFlow()

    /** Zeitpunkt des letzten Hinweises (monotone ms), 0 = noch keiner. */
    @Volatile
    private var lastAt = 0L

    /** Hinweis nach [lastAt] jetzt ([now]) zeigen? Erster immer, danach erst nach [MIN_GAP_MILLIS]; rückwärts laufende Zeit zählt nicht als «zu früh». */
    fun shouldNotify(lastAt: Long, now: Long): Boolean = lastAt == 0L || now < lastAt || now - lastAt >= MIN_GAP_MILLIS

    /** Ablehnung melden; gedrosselt nach [shouldNotify]. true = Ereignis ausgelöst. */
    @Synchronized
    fun report(now: Long = System.nanoTime() / 1_000_000): Boolean {
        if (!shouldNotify(lastAt, now)) return false
        // 0 ist «noch keiner»: einen echten Zeitpunkt 0 als 1 merken
        lastAt = now.coerceAtLeast(1L)
        refused.tryEmit(Unit)
        return true
    }
}
