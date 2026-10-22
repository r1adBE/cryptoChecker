package com.cryptochecker.app.data.live

import com.cryptochecker.app.data.PairIdentity
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.live.LiveBackoff
import com.cryptochecker.app.domain.live.LiveBuffer
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveParser
import com.cryptochecker.app.domain.live.LivePlanner
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.LivePriceApplier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live-Kurse per WebSocket für die SICHTBARE Merkliste (Runde 31), nur solange die App im
 * Vordergrund ist, die Merkliste angezeigt wird, das Gerät online ist und «Live-Kurse in der
 * App» eingeschaltet ist. Je Börse eine Verbindung (bei vielen Paaren mehrere, siehe
 * [LivePlanner]); Börsen ohne WebSocket bleiben bei der REST-Abfrage im gewohnten Takt.
 *
 * - Anzeige: [prices] höchstens zweimal je Sekunde ([LiveRules.UI_INTERVAL_MILLIS]).
 * - Datenbank: gesammelt alle 10 s über [LivePriceApplier.applyLive] — derselbe Weg für Alarme
 *   und Kurs-Meldungen wie bei einer Aktualisierung; Widgets höchstens jede Minute und beim
 *   Verlassen der Merkliste.
 * - Neu verbinden mit wachsender Pause ([LiveBackoff]); pausierte Börsen ([ExchangeBackoff])
 *   werden nicht verbunden. Ping je nach Börse als Text, sonst WebSocket-Ping-Frames.
 *
 * Kein WebSocket im Hintergrund oder in Widgets. Siehe DEVELOPMENT.md, «Live prices».
 */
@Singleton
class LivePriceStream @Inject constructor(
    baseClient: OkHttpClient,
    settingsRepository: SettingsRepository,
    connectivity: ConnectivityMonitor,
    private val refreshStats: RefreshStats,
    private val livePriceApplier: LivePriceApplier,
    private val coverage: LiveCoverage,
) {
    /** Eigener Client des Live-Stroms ([LiveDispatcher.client]): ohne Zeitgrenzen, mit User-Agent, eigener Dispatcher. */
    private val client: OkHttpClient = LiveDispatcher.client(baseClient, PROTOCOL_PING_SECONDS)

    private val scope = appScope(Dispatchers.IO, "LivePriceStream")

    /** Paare der angezeigten Merkliste (gewählte Gruppe). */
    private val screenPairs = MutableStateFlow<List<LivePair>>(emptyList())

    /** Merkliste gerade zu sehen (Bildschirm gestartet)? */
    private val screenVisible = MutableStateFlow(false)

    private val _prices = MutableStateFlow<Map<Long, LiveQuote>>(emptyMap())

    /** Live-Kurse je Watch-Id für die Anzeige (höchstens zweimal je Sekunde neu). */
    val prices: StateFlow<Map<Long, LiveQuote>> = _prices.asStateFlow()

    private val _liveExchanges = MutableStateFlow<List<String>>(emptyList())

    /** Namen der Börsen, deren Strom gerade Kurse liefert (Status-Pille «LIVE», Bericht). */
    val liveExchanges: StateFlow<List<String>> = _liveExchanges.asStateFlow()

    private val _liveWatchIds = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Watch-Ids der Paare am Live-Strom: abonniert bei einer Börse, die in den letzten
     * [LiveRules.FRESH_MILLIS] Kurse lieferte (eigene Frische-Schwelle, «Live» in der Pille).
     */
    val liveWatchIds: StateFlow<Set<Long>> = _liveWatchIds.asStateFlow()

    // ── Zustand unter [lock] ─────────────────────────────────────────────────────────────
    private val lock = Any()
    private val buffer = LiveBuffer()
    private var watchIds: Map<LiveExchange, Map<String, List<Long>>> = emptyMap()

    /**
     * Für welches Paar jede Watch-Id abonniert ist. Geht mit jedem Kurs an [LivePriceApplier.applyLive]:
     * ein Kurs des alten Paars nach «Paar bearbeiten» wird dort verworfen. Ändert sich das Paar einer
     * Id, vergisst [reconcile] ihren Stand im Puffer (unter derselben Sperre).
     */
    private var pairKeys: Map<Long, PairIdentity> = emptyMap()
    private val lastDataAt = HashMap<LiveExchange, Long>()
    private var marketNames: Map<String, String> = emptyMap()

    /** Laufende Verbindungen (nur aus der Steuer-Coroutine verändert). */
    private val connections = HashMap<String, LiveConnection>()
    private var loopsJob: Job? = null
    private var lastWidgetAt = 0L

    init {
        scope.launch {
            combine(
                screenPairs,
                screenVisible,
                AppVisibility.flow,
                settingsRepository.settings.map { it.liveWebSocket }.distinctUntilChanged(),
                connectivity.online,
            ) { pairs, shown, appVisible, enabled, online ->
                pairs.takeIf { shown && appVisible && enabled && online }
            }
                .distinctUntilChanged()
                .collectLatest { pairs ->
                    try {
                        if (pairs.isNullOrEmpty()) stopAll() else reconcile(pairs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Live-Kurse: Steuerung fehlgeschlagen")
                    }
                }
        }
    }

    /** Paare der angezeigten Merkliste (gewählte Gruppe). Gleiche Liste: nichts geschieht. */
    fun setPairs(pairs: List<LivePair>) {
        screenPairs.value = pairs
    }

    /**
     * Merkliste zu sehen bzw. nicht mehr (anderer Tab, App im Hintergrund): beim Verlassen
     * Verbindungen schliessen und Ungespeichertes speichern.
     */
    fun setVisible(visible: Boolean) {
        screenVisible.value = visible
    }

    private suspend fun reconcile(pairs: List<LivePair>) {
        val now = System.currentTimeMillis()
        val paused = refreshStats.backoffStates()
            .filterValues { ExchangeBackoff.isPaused(it, now) }.keys
        val plan = LivePlanner.plan(pairs, paused)
        if (plan.isEmpty) {
            stopAll()
            return
        }
        val ids = plan.watchIds.values.flatMap { it.values.flatten() }.toSet()
        val keys = pairs.filter { it.watchId in ids }.associate { it.watchId to it.identity() }
        synchronized(lock) {
            // Gleiche Id, anderes Paar: Stand des alten Paars nicht weiterführen
            buffer.forget(keys.filter { (id, key) -> pairKeys[id].let { it != null && it != key } }.keys)
            watchIds = plan.watchIds
            pairKeys = keys
            marketNames = pairs.associate { it.marketKey to it.marketName }
            buffer.retain(ids)
            lastDataAt.keys.retainAll(plan.watchIds.keys)
        }
        val wanted = plan.connections.associateBy { it.key }
        // Weggefallene oder geänderte Verbindungen schliessen, neue öffnen
        val iterator = connections.entries.iterator()
        while (iterator.hasNext()) {
            val (key, connection) = iterator.next()
            if (wanted[key] != connection.spec) {
                connection.stop()
                iterator.remove()
            }
        }
        for ((key, spec) in wanted) {
            if (key !in connections) connections[key] = LiveConnection(spec, scope, client, refreshStats, this::onText).also { it.start() }
        }
        if (loopsJob?.isActive != true) loopsJob = scope.launch { runLoops() }
        Timber.i("Live-Kurse: %d Verbindungen, %d Paare", connections.size, ids.size)
    }

    private suspend fun stopAll() {
        loopsJob?.cancel()
        loopsJob = null
        if (connections.isNotEmpty()) Timber.i("Live-Kurse: beendet")
        connections.values.forEach { it.stop() }
        connections.clear()
        // Zuerst den Zustand leeren (ohne Unterbrechung), dann das zuletzt Empfangene speichern: So kann
        // nach dem Speichern nichts mehr den Stand einer schon neu gestarteten Sitzung löschen.
        val (batch, keys) = synchronized(lock) {
            (buffer.takeForDb() to pairKeys).also {
                buffer.clear()
                watchIds = emptyMap()
                pairKeys = emptyMap()
                lastDataAt.clear()
            }
        }
        coverage.clear()
        _prices.value = emptyMap()
        _liveExchanges.value = emptyList()
        _liveWatchIds.value = emptySet()
        // Widgets und Alarme sollen den neuesten Stand haben. Letztes Speichern wartet kurz auf die
        // Sperre (läuft gerade eine Aktualisierung, gab applyLive sofort auf und markUnsaved lief
        // ins Leere — der Puffer ist schon geleert): der Stapel bleibt bis dahin hier erhalten
        withContext(NonCancellable) { save(batch, keys, force = true, final = true) }
    }

    /**
     * Gepufferte Kurse dieser Paare vergessen («Paar bearbeiten», unter der Refresh-Sperre aufrufen).
     * Die Paar-Zuordnung bleibt bis zum nächsten [reconcile] die alte: Takte des alten Symbols, die
     * bis dahin noch kommen, tragen das alte Paar und werden in [LivePriceApplier.applyLive] verworfen.
     */
    fun forget(watchIds: Collection<Long>) {
        if (watchIds.isEmpty()) return
        synchronized(lock) { buffer.forget(watchIds) }
    }

    /** Alle gepufferten Kurse vergessen (Sicherung wiederherstellen: Ids können andere Paare werden). */
    fun forgetAll() {
        synchronized(lock) { buffer.clear() }
    }

    /** Anzeige alle 500 ms, Datenbank alle 10 s — nur solange Verbindungen bestehen. */
    private suspend fun runLoops() = coroutineScope {
        launch {
            while (isActive) {
                delay(LiveRules.UI_INTERVAL_MILLIS)
                publish()
            }
        }
        launch {
            while (isActive) {
                delay(LiveRules.DB_INTERVAL_MILLIS)
                flush(force = false)
            }
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        var liveIds: Set<Long> = emptySet()
        val (snapshot, live) = synchronized(lock) {
            val fresh = lastDataAt.filterValues { now - it <= LiveRules.FRESH_MILLIS }.keys
            val names = fresh
                .mapNotNull { marketNames[it.marketKey] }
                .distinct()
                .sorted()
            liveIds = fresh.flatMapTo(HashSet()) { ex -> watchIds[ex]?.values?.flatten().orEmpty() }
            buffer.takeForUi() to names
        }
        if (snapshot != null) _prices.value = snapshot
        if (_liveExchanges.value != live) _liveExchanges.value = live
        if (_liveWatchIds.value != liveIds) _liveWatchIds.value = liveIds
    }

    private suspend fun flush(force: Boolean) {
        val (batch, keys) = synchronized(lock) { buffer.takeForDb() to pairKeys }
        save(batch, keys, force)
    }

    /** [final]: letztes Speichern beim Beenden ([stopAll]) — auf die Sperre warten statt aufgeben. */
    private suspend fun save(batch: Map<Long, LiveQuote>, keys: Map<Long, PairIdentity>, force: Boolean, final: Boolean = false) {
        if (batch.isEmpty()) return
        val now = System.currentTimeMillis()
        val redraw = force || now - lastWidgetAt >= LiveRules.WIDGET_INTERVAL_MILLIS
        val saved = try {
            // Mit dem abonnierten Paar je Id: ohne Zuordnung (sollte nicht vorkommen) wird verworfen
            val pairs = keys.filterKeys { it in batch }
            if (final) livePriceApplier.applyLiveFinal(batch, redrawWidgets = redraw, pairs = pairs)
            else livePriceApplier.applyLive(batch, redrawWidgets = redraw, pairs = pairs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Live-Kurse: Speichern fehlgeschlagen")
            false
        }
        if (saved) {
            if (redraw) lastWidgetAt = now
        } else if (final) {
            // Puffer schon geleert (stopAll): kein «nächstes Mal» mehr
            Timber.w("Live-Kurse: letzte %d Kurse nicht gespeichert (Sperre nicht frei)", batch.size)
        } else {
            synchronized(lock) { buffer.markUnsaved(batch.keys) }
        }
    }

    /** Nachricht einer Börse verarbeiten; true = mindestens ein Kurs für ein sichtbares Paar. */
    private fun onText(exchange: LiveExchange, text: String): Boolean {
        val ticks = LiveParser.parse(exchange, text)
        if (ticks.isEmpty()) return false
        val now = System.currentTimeMillis()
        var any = false
        synchronized(lock) {
            val bySymbol = watchIds[exchange] ?: return false
            for (tick in ticks) {
                val ids = bySymbol[tick.symbol] ?: continue
                buffer.offer(ids, tick, now)
                if (tick.price != null) {
                    coverage.mark(ids, now)
                    any = true
                }
            }
            if (any) lastDataAt[exchange] = now
        }
        return any
    }

    /** Paar der Merkliste in der Form der Datenbank-Spalten ([PairIdentity]). */
    private fun LivePair.identity(): PairIdentity = PairIdentity(marketKey, base, quote, contract, pairId)

    private companion object {
        /** WebSocket-Ping-Frames (OkHttp); bleibt die Antwort aus, gilt die Verbindung als getrennt. */
        const val PROTOCOL_PING_SECONDS = 20L
    }
}
