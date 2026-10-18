package com.cryptochecker.app.data.remote.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Gleiche Anfragen, die gleichzeitig laufen, nur einmal ins Netz schicken: Wer dieselbe
 * URL (mit derselben Zeitgrenze; bei POST auch mit denselben Kopfzeilen und demselben Körper)
 * anfragt, während sie schon unterwegs ist, wartet auf dieselbe Antwort — etwa Mini-Chart,
 * Aktionsblatt und Aktualisierung für dasselbe Paar, oder die Einzelkurse mehrerer Hyperliquid-Paare
 * (dieselbe «metaAndAssetCtxs»-Abfrage). Nichts wird zwischengespeichert: Ist die Anfrage fertig,
 * fällt der Eintrag weg. Den Schlüssel bildet `callMarket` (MarketHttp.kt).
 *
 * Bricht der letzte Wartende ab (Blatt geschlossen, Bildschirm verlassen), wird auch die
 * Anfrage abgebrochen — Laden, das an einen Bildschirm gebunden ist, endet mit ihm.
 */
internal object InFlightRequests {

    private class Entry(val deferred: Deferred<String>) {
        var waiters = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = HashMap<String, Entry>()

    suspend fun shared(key: String, load: suspend () -> String): String {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry(scope.async(start = CoroutineStart.LAZY) { load() }) }
                .also { it.waiters++ }
        }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            synchronized(entries) {
                entry.waiters--
                if (entry.waiters == 0) {
                    if (entries[key] === entry) entries.remove(key)
                    if (!entry.deferred.isCompleted) entry.deferred.cancel()
                }
            }
        }
    }
}
