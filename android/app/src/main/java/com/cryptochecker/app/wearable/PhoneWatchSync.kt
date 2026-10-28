package com.cryptochecker.app.wearable

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchSettings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coin-Liste der Wear-OS-App vom Handy aus (Einstellungen › Widgets › Uhr): Wahl «Wie Handy» (Basis-Coins
 * der Favoriten ★ der Merkliste, höchstens 8) oder «Eigene Liste». Übertragen wird nur [WatchSettings] als JSON über
 * die Wear-Daten-Schicht (Google Play-Dienste) unter [PATH] — keine Kurse, Beträge oder Portfolio-Daten. Veröffentlicht
 * wird nur, wenn eine Uhr die App hat (Fähigkeit [CAPABILITY], auch wenn sie gerade nicht verbunden ist). Ohne Play-Dienste
 * oder ohne Uhr: nichts, die Einstellung bleibt lokal. Gegenstück: `WatchDataLayer` in `:wear`.
 */
@Singleton
class PhoneWatchSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchRepository: WatchRepository,
) {
    private val scope = appScope(Dispatchers.IO, "PhoneWatchSync")
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Mutex()
    private val _state = MutableStateFlow(WatchSettings())
    private var started = false

    /** Aktueller Stand (Wahl, eigene Liste, Liste aus den Favoriten). */
    val state: StateFlow<WatchSettings> = _state.asStateFlow()

    /**
     * Einmal beim Start des Prozesses: gespeicherten Stand laden und den Favoriten folgen. Die Play-Dienste werden erst
     * gefragt, wenn sich etwas ändert (Favoriten, Wahl) oder die Seite «Widgets» offen ist — nicht bei jedem Start.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            lock.withLock { _state.value = WatchSettings.fromJson(prefs.getString(KEY, null)) ?: WatchSettings() }
            watchRepository.observeWatches()
                .map { watches -> WatchCoins.fromFavorites(watches.filter { it.favorite }.sortedBy { it.sortOrder }.map { it.baseAsset }) }
                .distinctUntilChanged()
                .catch { }
                .collect { phone -> update(publish = true) { it.copy(phone = phone) } }
        }
    }

    /** Wahl «Wie Handy» / «Eigene Liste» ändern. */
    fun setMode(mode: WatchSettings.Mode) = scope.launch {
        update(publish = true) { it.copy(mode = mode).nextRevision() }
    }

    /** Coin der eigenen Liste an- oder abwählen (höchstens [WatchCoins.MAX]). */
    fun toggleOwn(coin: String) = scope.launch {
        update(publish = true) { it.copy(own = WatchCoins.toggle(it.own, coin)).nextRevision() }
    }

    /** Stand der Uhr im Hintergrund übernehmen (Seite «Widgets» geöffnet). */
    fun refreshFromWatch() = scope.launch { pullFromWatch() }

    /** Stand der Uhr holen und übernehmen ([take]). */
    suspend fun pullFromWatch() {
        take(watchSettings() ?: return)
    }

    /**
     * Geänderte Einträge ([changed]) aus der Daten-Schicht im Prozess-Scope übernehmen (läuft weiter, auch wenn der
     * Listener-Dienst schon beendet ist); der eigene Eintrag des Handys zählt nicht.
     */
    fun takeFromWatch(items: List<Item>) = scope.launch {
        take(runCatchingCancellable { newestRemote(items) }.getOrNull() ?: return@launch)
    }

    /** Wahl und eigene Liste der Uhr nur bei höherer Änderungsnummer (Gleichstand: eigene); Handy-Liste bleibt. */
    private suspend fun take(remote: WatchSettings) {
        update(publish = false) { WatchSettings.merge(it, remote, remoteIsPhone = false) }
    }

    private suspend fun update(publish: Boolean, change: (WatchSettings) -> WatchSettings) {
        val next = lock.withLock {
            val before = _state.value
            val after = change(before)
            if (after == before) return
            _state.value = after
            prefs.edit { putString(KEY, after.toJson()) }
            after
        }
        if (publish) publish(next)
    }

    private suspend fun publish(settings: WatchSettings) = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL),
                TIMEOUT_S, TimeUnit.SECONDS
            ).nodes
            if (nodes.isEmpty()) return@runCatchingCancellable
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString(DATA_KEY, settings.toJson()) }
                .asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    /** Stand der Uhr (Einträge anderer Geräte unter [PATH]); null = keiner oder keine Play-Dienste. */
    private suspend fun watchSettings(): WatchSettings? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val buffer = Tasks.await(Wearable.getDataClient(context).getDataItems("wear://*$PATH".toUri()),
                TIMEOUT_S, TimeUnit.SECONDS)
            val items = try {
                buffer.map { Item(it.uri.host, DataMapItem.fromDataItem(it).dataMap.getString(DATA_KEY)) }
            } finally {
                buffer.release()
            }
            newestRemote(items)
        }.getOrNull()
    }

    /** Neuester Stand (höchste Änderungsnummer) unter [items], ohne den eigenen Eintrag des Handys. Blockiert (IO). */
    private fun newestRemote(items: List<Item>): WatchSettings? {
        if (items.isEmpty()) return null
        val local = Tasks.await(Wearable.getNodeClient(context).localNode, TIMEOUT_S, TimeUnit.SECONDS).id
        return items.filter { it.node != local }.mapNotNull { WatchSettings.fromJson(it.json) }.maxByOrNull { it.revision }
    }

    /** Eintrag der Daten-Schicht: Gerät (Host der Adresse) und JSON. */
    class Item(val node: String?, val json: String?)

    companion object {
        const val PATH = "/cc/watch"
        const val CAPABILITY = "cryptochecker_watch"
        private const val DATA_KEY = "json"
        private const val PREFS = "watch_sync"
        private const val KEY = "settings"
        private const val TIMEOUT_S = 10L

        /** Geänderte Einträge unter [PATH] aus [events] — sofort gelesen, solange der Puffer gültig ist. */
        fun changed(events: DataEventBuffer): List<Item> = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH }
            .map { Item(it.dataItem.uri.host, DataMapItem.fromDataItem(it.dataItem).dataMap.getString(DATA_KEY)) }
    }
}
