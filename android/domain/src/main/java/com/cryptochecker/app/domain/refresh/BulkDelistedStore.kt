package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.util.KeyValueStores
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stand von [BulkDelisted] je Börse, dauerhaft in einer kleinen Einstellungsdatei. Nur im Speicher hätte jeder
 * Prozess-Start (Worker, exakter Wecker) keinen Stand — bei lauter «nicht mehr gehandelt»-Paaren folgte dann jedes
 * Mal eine Neuprüfung, bei Binance mit der ungefilterten Liste (~2 MB). Abgeleiteter Marktwert, nicht in der
 * Sicherung. Wie `BulkDelistedMemory` (iOS).
 */
@Singleton
class BulkDelistedStore @Inject constructor(
    private val stores: KeyValueStores,
) {
    private val prefs by lazy { stores.open(PREFS) }

    /** Beim ersten Zugriff aus der Datei geladen; danach massgebend für diesen Prozess. */
    private val states by lazy { ConcurrentHashMap(BulkDelisted.decode(prefs.getString(KEY))) }

    operator fun get(marketKey: String): BulkDelisted.State? = states[marketKey]

    /** Schreibt nur bei einer Änderung; synchronisiert, damit eine ältere Fassung keine neuere überschreibt. */
    @Synchronized
    fun put(marketKey: String, state: BulkDelisted.State) {
        if (states.put(marketKey, state) == state) return
        prefs.edit { putString(KEY, BulkDelisted.encode(states)) }
    }

    private companion object {
        const val PREFS = "bulk_delisted"
        const val KEY = "states"
    }
}
