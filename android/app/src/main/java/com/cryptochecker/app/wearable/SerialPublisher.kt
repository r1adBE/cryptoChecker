package com.cryptochecker.app.wearable

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Veröffentlicht den Stand der Wahl nacheinander statt gleichzeitig: [publish] wartet auf die Sperre (fair, also in der
 * Reihenfolge der Aufrufe) und liest den Stand ([current]) erst dann. Das letzte Senden trägt so immer den neuesten
 * Stand, auch wenn zwei schnelle Änderungen ihre Sendungen überholen würden. Gleich auf Uhr und Handy: bewusst je
 * eine Kopie in :app und :wear, weil das einzige gemeinsame Modul (:marketdata) ohne Coroutines bleibt.
 */
internal class SerialPublisher {
    private val lock = Mutex()

    suspend fun <T> publish(current: () -> T, send: suspend (T) -> Unit) {
        lock.withLock { send(current()) }
    }
}
