package com.cryptochecker.app.wearable

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.util.appScope
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchInterval
import com.cryptochecker.marketdata.watch.WatchPair
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
 * der Favoriten ★ der Merkliste, höchstens 8, je Coin mit dem Paar des Favoriten, [favorites]) oder «Eigene Liste»,
 * dazu die Kursquelle der Uhr ([WatchSettings.source]) und das Intervall der Kurs-Aktualisierung ([WatchSettings.interval], [WatchInterval.clamp]). Übertragen wird
 * [WatchSettings] als JSON über die Wear-Daten-Schicht (Google Play-Dienste) unter [PATH]; Kurse und Portfolio-Wert
 * schickt [PhoneWatchFeed] über [put]. Veröffentlicht wird nur, wenn eine Uhr die App hat (Fähigkeit [CAPABILITY], auch
 * wenn sie gerade nicht verbunden ist). Ohne Play-Dienste oder ohne Uhr: nichts, die Einstellung bleibt lokal.
 * Gegenstück: `WatchDataLayer` in `:wear`.
 */
@Singleton
class PhoneWatchSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = appScope(Dispatchers.IO, "PhoneWatchSync")
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Mutex()
    private val changing = Mutex()
    private val publishing = SerialPublisher()
    private val _state = MutableStateFlow(WatchSettings())
    private var started = false

    /** Gespeicherter Stand schon in [_state] geladen; nur unter [lock] lesen und setzen. */
    private var loaded = false

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
            lock.withLock { ensureLoaded() }
            watchRepository.observeWatches()
                .map { watches -> favorites(watches) }
                .distinctUntilChanged()
                .catch { }
                .collect { (phone, pairs) -> update(publish = true) { it.copy(phone = phone, pairs = pairs) } }
        }
        // Intervall der Kurs-Aktualisierung: die Uhr lädt im selben Takt (begrenzt, [WatchInterval])
        scope.launch {
            settingsRepository.settings
                .map { WatchInterval.clamp(it.backgroundIntervalMinutes) }
                .distinctUntilChanged()
                .catch { }
                .collect { minutes -> update(publish = true) { it.copy(interval = minutes) } }
        }
    }

    /** «Kurse vom Handy, sonst selbst» / «Immer selbst abrufen» — bestimmt nur das Handy (keine Änderungsnummer). */
    fun setSource(source: WatchSettings.Source) = scope.launch {
        update(publish = true) { it.copy(source = source) }
    }

    /** Wahl «Wie Handy» / «Eigene Liste» ändern. */
    fun setMode(mode: WatchSettings.Mode) = scope.launch {
        change { it.copy(mode = mode) }
    }

    /** Coin der eigenen Liste an- oder abwählen (höchstens [WatchCoins.MAX]). */
    fun toggleOwn(coin: String) = scope.launch {
        change { it.copy(own = WatchCoins.toggle(it.own, coin)) }
    }

    /**
     * Eigene Änderung von Wahl oder eigener Liste: zuerst den neuesten Stand der Uhr aus der Daten-Schicht (auch ohne
     * Verbindung der zuletzt bekannte) zusammenführen, dann mit max(Handy, Uhr) + 1 speichern und veröffentlichen
     * ([WatchSettings.change]) — sonst bliebe die Uhr bei ihrer Wahl, wenn sie schon weiter gezählt hatte. Nacheinander
     * ([changing]), damit zwei schnelle Änderungen nicht dieselbe Nummer bekommen.
     */
    private suspend fun change(edit: (WatchSettings) -> WatchSettings) {
        changing.withLock {
            val watch = watchSettings()
            update(publish = true) { WatchSettings.change(it, watch, isPhone = true, edit) }
        }
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

    /**
     * Gespeicherten Stand einmal laden, bevor etwas zusammengeführt wird: startet der Prozess über [PhoneWatchListener]
     * oder ändert sich die Wahl, bevor [start] geladen hat, darf keine Änderung gegen den leeren Anfangsstand
     * (Änderungsnummer 0) laufen und den gespeicherten Stand überschreiben. Nur unter [lock].
     */
    private fun ensureLoaded() {
        if (loaded) return
        _state.value = WatchSettings.fromJson(prefs.getString(KEY, null)) ?: WatchSettings()
        loaded = true
    }

    /**
     * Ändern und speichern unter [lock] (nach [ensureLoaded]); veröffentlicht wird danach nacheinander
     * ([SerialPublisher]) und immer der aktuelle Stand, nicht der Wert dieser Änderung.
     */
    private suspend fun update(publish: Boolean, change: (WatchSettings) -> WatchSettings) {
        lock.withLock {
            ensureLoaded()
            val before = _state.value
            val after = change(before)
            if (after == before) return
            _state.value = after
            prefs.edit { putString(KEY, after.toJson()) }
        }
        if (publish) publishing.publish({ _state.value }) { publish(it) }
    }

    private suspend fun publish(settings: WatchSettings) = put(PATH, settings.toJson())

    /**
     * [json] unter [path] in die Daten-Schicht legen (dringend, damit die Uhr es gleich bekommt) — nur, wenn eine Uhr die
     * App hat; Fehler bleiben still. Gleicher Inhalt wie zuvor löst auf der Uhr keine Änderung aus.
     */
    internal suspend fun put(path: String, json: String) = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL),
                TIMEOUT_S, TimeUnit.SECONDS
            ).nodes
            if (nodes.isEmpty()) return@runCatchingCancellable
            val request = PutDataMapRequest.create(path).apply { dataMap.putString(DATA_KEY, json) }
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

        /**
         * «Wie Handy»: Basis-Coins der Favoriten ([WatchCoins.fromFavorites]) und je Coin das Paar des ersten Favoriten
         * ([WatchPair]: Börse und Kontrakt wie in der Merkliste, z. B. BANK/USDT Perp bei Binance Futures).
         */
        internal fun favorites(watches: List<WatchEntity>): Pair<List<String>, List<WatchPair>> {
            val favorites = watches.filter { it.favorite }.sortedBy { it.sortOrder }
            val bases = favorites.map { it.baseAsset }
            val pairs = favorites.map {
                WatchPair.of(it.baseAsset, it.quoteAsset, it.exchangeName, WatchPair.contractOf(it.contractShortName, it.pairId != null))
            }
            return WatchCoins.fromFavorites(bases) to WatchCoins.favoritePairs(bases, pairs)
        }

        /** Geänderte Einträge unter [PATH] aus [events] — sofort gelesen, solange der Puffer gültig ist. */
        fun changed(events: DataEventBuffer): List<Item> = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH }
            .map { Item(it.dataItem.uri.host, DataMapItem.fromDataItem(it.dataItem).dataMap.getString(DATA_KEY)) }
    }
}
