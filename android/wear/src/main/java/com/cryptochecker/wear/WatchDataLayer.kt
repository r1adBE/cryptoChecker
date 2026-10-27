package com.cryptochecker.wear

import android.content.Context
import android.net.Uri
import com.cryptochecker.marketdata.watch.WatchSettings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abgleich der Wahl mit dem Handy über die Wear-Daten-Schicht (Build 154): je Gerät ein Eintrag unter [PATH] mit dem
 * JSON von [WatchSettings] — nur Coin-Kürzel und Wahl, keine Kurse oder Beträge. Gleich in der Handy-App
 * (`PhoneWatchSync`). Ohne Google Play-Dienste oder ohne Handy: nichts (die Uhr nutzt ihre eigene Liste).
 */
object WatchDataLayer {
    const val PATH = "/cc/watch"
    private const val KEY = "json"
    private const val TIMEOUT_S = 10L

    /** Eigenen Stand veröffentlichen. */
    suspend fun publish(context: Context, settings: WatchSettings) = withContext(Dispatchers.IO) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString(KEY, settings.toJson()) }
                .asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** Stand des Handys (Einträge anderer Geräte); null = keiner. */
    suspend fun phoneSettings(context: Context): WatchSettings? = withContext(Dispatchers.IO) {
        runCatching {
            val local = Tasks.await(Wearable.getNodeClient(context).localNode, TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).id
            val buffer = Tasks.await(Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*$PATH")),
                TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            try {
                buffer.filter { it.uri.host != local }
                    .mapNotNull { WatchSettings.fromJson(DataMapItem.fromDataItem(it).dataMap.getString(KEY)) }
                    .maxByOrNull { it.time }
            } finally {
                buffer.release()
            }
        }.getOrNull()
    }

    /** Stand des Handys übernehmen (Wahl/eigene Liste, wenn neuer; Handy-Liste immer) und speichern. */
    suspend fun pull(context: Context, store: WatchStore): WatchSettings {
        val phone = phoneSettings(context) ?: return store.settings
        val merged = WatchSettings.merge(store.settings, phone, remoteIsPhone = true)
        store.settings = merged
        return merged
    }
}
