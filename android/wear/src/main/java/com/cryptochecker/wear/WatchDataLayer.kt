package com.cryptochecker.wear

import android.content.Context
import androidx.core.net.toUri
import com.cryptochecker.marketdata.watch.WatchSettings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Abgleich der Wahl mit dem Handy über die Wear-Daten-Schicht: je Gerät ein Eintrag unter [PATH] mit dem
 * JSON von [WatchSettings] — nur Coin-Kürzel und Wahl, keine Kurse oder Beträge. Gleich in der Handy-App
 * (`PhoneWatchSync`). Ohne Google Play-Dienste oder ohne Handy: nichts (die Uhr nutzt ihre eigene Liste).
 */
object WatchDataLayer {
    const val PATH = "/cc/watch"
    private const val KEY = "json"
    private const val TIMEOUT_S = 10L

    /** Geänderter Eintrag: Gerät (Host der Adresse) und JSON. */
    class Item(val node: String?, val json: String?)

    /** Eigenen Stand veröffentlichen. */
    suspend fun publish(context: Context, settings: WatchSettings) = withContext(Dispatchers.IO) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString(KEY, settings.toJson()) }
                .asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    /** Geänderte Einträge unter [PATH] aus [events] — sofort gelesen, solange der Puffer gültig ist. */
    fun changed(events: DataEventBuffer): List<Item> = events
        .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH }
        .map { Item(it.dataItem.uri.host, DataMapItem.fromDataItem(it.dataItem).dataMap.getString(KEY)) }

    /** Stand des Handys (Einträge anderer Geräte); null = keiner. */
    private suspend fun phoneSettings(context: Context): WatchSettings? = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = Tasks.await(Wearable.getDataClient(context).getDataItems("wear://*$PATH".toUri()),
                TIMEOUT_S, TimeUnit.SECONDS)
            val items = try {
                buffer.map { Item(it.uri.host, DataMapItem.fromDataItem(it).dataMap.getString(KEY)) }
            } finally {
                buffer.release()
            }
            newestRemote(context, items)
        }.getOrNull()
    }

    /** Neuester Stand (höchste Änderungsnummer) unter [items], ohne den eigenen Eintrag der Uhr. */
    private fun newestRemote(context: Context, items: List<Item>): WatchSettings? {
        if (items.isEmpty()) return null
        val local = Tasks.await(Wearable.getNodeClient(context).localNode, TIMEOUT_S, TimeUnit.SECONDS).id
        return items.filter { it.node != local }.mapNotNull { WatchSettings.fromJson(it.json) }.maxByOrNull { it.revision }
    }

    /** Stand des Handys holen und übernehmen ([take]). */
    suspend fun pull(context: Context, store: WatchStore) {
        phoneSettings(context)?.let { take(store, it) }
    }

    /** Geänderte Einträge ([changed]) übernehmen; true = ein Stand des Handys war dabei. */
    suspend fun take(context: Context, store: WatchStore, items: List<Item>): Boolean {
        val phone = withContext(Dispatchers.IO) { runCatching { newestRemote(context, items) }.getOrNull() } ?: return false
        take(store, phone)
        return true
    }

    /** Wahl und eigene Liste des Handys bei höherer oder gleicher Änderungsnummer, die Handy-Liste immer; im [store]. */
    private fun take(store: WatchStore, phone: WatchSettings) {
        store.updateSettings { WatchSettings.merge(it, phone, remoteIsPhone = true) }
    }
}
