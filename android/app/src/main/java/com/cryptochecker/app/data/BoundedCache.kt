package com.cryptochecker.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Übliche Grenze für Zwischenspeicher je Paar bzw. Coin im Arbeitsspeicher: weit über einer
 * gewöhnlichen Merkliste bzw. einem Portfolio, aber nicht unbegrenzt über die Lebensdauer des Prozesses.
 */
const val MEMORY_CACHE_ENTRIES = 512

/**
 * Thread-sicherer Zwischenspeicher mit höchstens [maxEntries] Einträgen: der am längsten nicht benutzte fällt
 * heraus (LRU über `LinkedHashMap` in Zugriffsreihenfolge). Statt einer unbegrenzten
 * `ConcurrentHashMap` — Paare, die einmal in der Merkliste standen, blieben sonst bis zum Prozessende im Speicher.
 */
class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }

    /** Wie `ConcurrentHashMap.merge`: vorhandener Wert und [value] ergeben über [remap] den neuen. */
    fun merge(key: K, value: V, remap: (V, V) -> V) {
        synchronized(map) {
            val current = map[key]
            map[key] = if (current == null) value else remap(current, value)
        }
    }

    /** Wie `ConcurrentHashMap.putIfAbsent`: nur ohne vorhandenen Eintrag setzen; gibt den vorhandenen zurück. */
    fun putIfAbsent(key: K, value: V): V? = synchronized(map) {
        map[key] ?: run {
            map[key] = value
            null
        }
    }

    fun remove(key: K): V? = synchronized(map) { map.remove(key) }

    /** Alle Einträge entfernen, deren Schlüssel [predicate] erfüllt (z. B. alle eines Paars). */
    fun removeKeysIf(predicate: (K) -> Boolean) {
        synchronized(map) { map.keys.removeAll(predicate) }
    }

    /** Abzug aller Einträge (zum Speichern), ohne die Zugriffsreihenfolge zu ändern. */
    fun snapshot(): Map<K, V> = synchronized(map) { LinkedHashMap(map) }

    fun clear() {
        synchronized(map) { map.clear() }
    }

    val size: Int get() = synchronized(map) { map.size }
}

/**
 * Ein `Mutex` je Schlüssel: gleichzeitige Abrufe desselben Schlüssels warten aufeinander. Der Mutex bleibt nur,
 * solange jemand ihn hält oder darauf wartet (Zähler), danach wird er entfernt — so wächst die Liste nicht mit
 * jedem je abgefragten Paar, und der gegenseitige Ausschluss bleibt: wer zum selben Schlüssel kommt, solange noch
 * jemand dran ist, bekommt denselben Mutex.
 */
class KeyedMutex<K : Any> {
    private class Slot {
        val mutex = Mutex()
        var users = 0
    }

    private val slots = HashMap<K, Slot>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val slot = synchronized(slots) { slots.getOrPut(key) { Slot() }.also { it.users++ } }
        try {
            return slot.mutex.withLock { block() }
        } finally {
            // Auch bei Abbruch während des Wartens: abmelden, der letzte räumt auf
            synchronized(slots) {
                slot.users--
                if (slot.users == 0) slots.remove(key)
            }
        }
    }

    /** Anzahl gerade benutzter Schlüssel (für Tests). */
    val size: Int get() = synchronized(slots) { slots.size }
}
