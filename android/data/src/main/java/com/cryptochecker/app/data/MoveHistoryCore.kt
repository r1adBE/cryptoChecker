package com.cryptochecker.app.data

import com.cryptochecker.app.domain.alarm.MoveWindow
import org.json.JSONArray

/** Ablage der Verläufe ([MoveHistoryCore]): Text je Schlüssel; in [write] steht null für «entfernen». */
interface MoveHistoryStorage {
    fun read(key: String): String?
    fun write(changes: Map<String, String?>)
    fun keys(): Set<String>
}

/**
 * Kern von [MoveHistoryStore]: ein Schlüssel je Paar — ein Paar liest und schreibt nur den eigenen Eintrag (auch
 * bei vielen Paaren mit Bewegungs-Alarm im Live-Modus alle 10 s nur O(N) je Paar). Fremde, abgelaufene Verläufe
 * (gelöschte Paare, Alarm entfernt) räumt höchstens alle [CLEANUP_INTERVAL_MILLIS] ein Durchgang weg. Gleich wie iOS.
 */
class MoveHistoryCore(private val storage: MoveHistoryStorage) {
    private val lock = Any()
    private var lastCleanupAt: Long? = null

    /** Dekodierte Verläufe seit dem Start (für den Test: append bleibt O(N)). */
    var decodes = 0
        private set

    /** Gespeicherter Verlauf des Paars, älteste zuerst. */
    fun history(watchId: Long): List<MoveWindow.PricePoint> = synchronized(lock) {
        decode(storage.read(key(watchId)))
    }

    /** Punkt anhängen ([MoveWindow.append]); schreibt nur, was sich ändert. */
    fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long) {
        synchronized(lock) { appendLocked(watchId, point, now) }
    }

    /** [append] unter [lock]. */
    private fun appendLocked(watchId: Long, point: MoveWindow.PricePoint, now: Long) {
        val key = key(watchId)
        val before = decode(storage.read(key))
        val updated = MoveWindow.append(before, point, now)
        val changes = HashMap<String, String?>()
        // Live-Kurse kommen sekündlich: meist kein neuer Punkt — dann nichts schreiben
        if (updated != before) changes[key] = encode(updated)
        if (cleanupDue(now)) {
            lastCleanupAt = now
            staleKeys(now, except = key).forEach { changes[it] = null }
        }
        if (changes.isNotEmpty()) storage.write(changes)
    }

    private fun cleanupDue(now: Long): Boolean {
        val last = lastCleanupAt ?: return true
        // Uhr zurückgestellt: gleich aufräumen statt lange warten
        return now - last !in 0 until CLEANUP_INTERVAL_MILLIS
    }

    /** Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist (oder unlesbar). */
    private fun staleKeys(now: Long, except: String): List<String> =
        storage.keys().filter { it.startsWith(PREFIX) && it != except }.filter { other ->
            val last = decode(storage.read(other)).maxOfOrNull { it.time }
            last == null || now - last > MoveWindow.RETENTION_MILLIS
        }

    private fun decode(text: String?): List<MoveWindow.PricePoint> {
        if (text == null) return emptyList()
        decodes++
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONArray(i) ?: return@mapNotNull null
            MoveWindow.PricePoint(price = item.optDouble(1, 0.0), time = item.optLong(0, 0L))
        }
    }

    private fun encode(points: List<MoveWindow.PricePoint>): String {
        val array = JSONArray()
        points.forEach { array.put(JSONArray().put(it.time).put(it.price)) }
        return array.toString()
    }

    companion object {
        /** Schlüssel je Paar: «h_<watchId>». */
        const val PREFIX = "h_"
        const val CLEANUP_INTERVAL_MILLIS = 30 * 60_000L

        fun key(watchId: Long): String = PREFIX + watchId
    }
}
