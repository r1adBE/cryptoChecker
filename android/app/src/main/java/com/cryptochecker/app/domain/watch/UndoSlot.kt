package com.cryptochecker.app.domain.watch

/**
 * Ein Platz für «Rückgängig» (reines Kotlin, testbar): Nur die zuletzt gelöschte
 * Sache lässt sich zurückholen. Eine neue Löschung ersetzt die vorige; [take]
 * gibt den Inhalt genau einmal heraus und nur für den passenden Schlüssel —
 * ein verspätetes «Rückgängig» für eine ältere Löschung tut nichts.
 * Nicht threadsicher: Aufrufer serialisieren (z. B. mit einem Mutex).
 */
class UndoSlot<K, V> {
    private var entry: Pair<K, V>? = null

    fun put(key: K, value: V) {
        entry = key to value
    }

    /** Inhalt für [key] herausnehmen; null, wenn inzwischen etwas anderes gelöscht wurde. */
    fun take(key: K): V? {
        val current = entry ?: return null
        if (current.first != key) return null
        entry = null
        return current.second
    }

    /** Ohne herauszunehmen: liegt für [key] etwas bereit? */
    fun holds(key: K): Boolean = entry?.first == key

    fun clear() {
        entry = null
    }
}
