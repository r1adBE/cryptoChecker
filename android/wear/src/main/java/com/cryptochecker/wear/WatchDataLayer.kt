package com.cryptochecker.wear

import android.content.Context
import androidx.core.net.toUri
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchPortfolio
import com.cryptochecker.marketdata.watch.WatchSettings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Abgleich mit dem Handy über die Wear-Daten-Schicht: je Gerät ein Eintrag unter [PATH] mit dem JSON von
 * [WatchSettings] (Coin-Kürzel, Wahl, Kursquelle, Intervall); dazu vom Handy die Kurse, die es schon hat, unter
 * [QUOTES_PATH] ([WatchPhoneQuotes]) und — nur mit dem Schalter auf dem Handy — der Portfolio-Wert unter
 * [PORTFOLIO_PATH] ([WatchPortfolio]). Gegenstück: `PhoneWatchSync` / `PhoneWatchFeed` in der Handy-App. Ohne Google
 * Play-Dienste oder ohne Handy: nichts (die Uhr nutzt ihre eigene Liste und fragt die Kurse selbst ab).
 */
object WatchDataLayer {
    const val PATH = "/cc/watch"
    const val QUOTES_PATH = "/cc/watch/quotes"
    const val PORTFOLIO_PATH = "/cc/watch/portfolio"
    private const val KEY = "json"
    private const val TIMEOUT_S = 10L

    /** Scope für die Lebensdauer des Prozesses (nie abgebrochen); ein Fehler betrifft nur seinen eigenen Job. */
    val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val publishing = SerialPublisher()

    /** Geänderter Eintrag: Gerät (Host der Adresse), Pfad und JSON. */
    class Item(val node: String?, val json: String?, val path: String? = PATH)

    /** Was eine Übernahme geändert hat: Wahl, Kurse vom Handy, Portfolio-Wert. */
    class Taken(val settings: Boolean, val quotes: Boolean, val portfolio: Boolean)

    /**
     * Eigenen Stand veröffentlichen, nach jeder Änderung auf der Uhr: nacheinander ([SerialPublisher]) und immer mit dem
     * aktuellen Stand aus [store], nicht mit dem Wert zum Zeitpunkt der Änderung. Läuft in [processScope] weiter, auch
     * wenn die Uhr-App schon geschlossen ist.
     */
    fun publish(context: Context, store: WatchStore) {
        processScope.launch { publishing.publish({ store.settings }) { publish(context, it) } }
    }

    private suspend fun publish(context: Context, settings: WatchSettings) = withContext(Dispatchers.IO) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString(KEY, settings.toJson()) }
                .asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    private val paths = setOf(PATH, QUOTES_PATH, PORTFOLIO_PATH)

    /** Geänderte Einträge unter [paths] aus [events] — sofort gelesen, solange der Puffer gültig ist. */
    fun changed(events: DataEventBuffer): List<Item> = events
        .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path in paths }
        .map { Item(it.dataItem.uri.host, DataMapItem.fromDataItem(it.dataItem).dataMap.getString(KEY), it.dataItem.uri.path) }

    /** Einträge aller Geräte unter [path]; leer = keine oder keine Play-Dienste. */
    private suspend fun items(context: Context, path: String): List<Item> = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = Tasks.await(Wearable.getDataClient(context).getDataItems("wear://*$path".toUri()),
                TIMEOUT_S, TimeUnit.SECONDS)
            try {
                buffer.map { Item(it.uri.host, DataMapItem.fromDataItem(it).dataMap.getString(KEY), path) }
            } finally {
                buffer.release()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Handy gerade verbunden (in der Nähe oder über die Cloud)? Ohne Play-Dienste oder bei Fehler: nein — dann fragt die
     * Uhr selbst ab ([WatchPhoneQuotes.selfFetch]).
     */
    suspend fun phoneConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS).isNotEmpty()
        }.getOrDefault(false)
    }

    /** Neuester Stand (höchste Änderungsnummer) unter [items], ohne den eigenen Eintrag der Uhr. */
    private fun newestRemote(context: Context, items: List<Item>): WatchSettings? {
        if (items.isEmpty()) return null
        val local = Tasks.await(Wearable.getNodeClient(context).localNode, TIMEOUT_S, TimeUnit.SECONDS).id
        return items.filter { it.node != local }.mapNotNull { WatchSettings.fromJson(it.json) }.maxByOrNull { it.revision }
    }

    /** Stand des Handys holen und übernehmen ([take]): Wahl, Kurse und Portfolio-Wert. */
    suspend fun pull(context: Context, store: WatchStore): Taken =
        take(context, store, paths.flatMap { items(context, it) })

    /** Geänderte Einträge ([changed]) übernehmen: Wahl (ohne den eigenen Eintrag), Kurse und Portfolio-Wert vom Handy. */
    suspend fun take(context: Context, store: WatchStore, items: List<Item>): Taken {
        val settings = items.filter { it.path == PATH }
        val phone = if (settings.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching { newestRemote(context, settings) }.getOrNull()
        }
        phone?.let { take(store, it) }
        val quotes = items.lastOrNull { it.path == QUOTES_PATH }?.let { WatchPhoneQuotes.fromJson(it.json) }
        quotes?.let { store.takePhoneQuotes(it) }
        val portfolio = items.lastOrNull { it.path == PORTFOLIO_PATH }?.let { WatchPortfolio.fromJson(it.json) }
        portfolio?.let { store.takePortfolio(it) }
        return Taken(phone != null, quotes != null, portfolio != null)
    }

    /** Wahl und eigene Liste des Handys bei höherer oder gleicher Änderungsnummer, die Handy-Liste immer; im [store]. */
    private fun take(store: WatchStore, phone: WatchSettings) {
        store.updateSettings { WatchSettings.merge(it, phone, remoteIsPhone = true) }
    }
}
