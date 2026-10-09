package com.cryptochecker.app.data.live

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.live.LiveBackoff
import com.cryptochecker.app.domain.live.LiveBuffer
import com.cryptochecker.app.domain.live.LiveConnectionSpec
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveParser
import com.cryptochecker.app.domain.live.LivePlanner
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.ConnectivityMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live-Kurse per WebSocket für die SICHTBARE Merkliste (Runde 31), nur solange die App im
 * Vordergrund ist, die Merkliste angezeigt wird, das Gerät online ist und «Live-Kurse in der
 * App» eingeschaltet ist. Je Börse eine Verbindung (bei vielen Paaren mehrere, siehe
 * [LivePlanner]); Börsen ohne WebSocket bleiben bei der REST-Abfrage im gewohnten Takt.
 *
 * - Anzeige: [prices] höchstens zweimal je Sekunde ([LiveRules.UI_INTERVAL_MILLIS]).
 * - Datenbank: gesammelt alle 10 s über [PriceRefresher.applyLive] — derselbe Weg für Alarme
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
    private val priceRefresher: PriceRefresher,
    private val coverage: LiveCoverage,
) {
    /** Ohne Gesamt-Zeitgrenze und Protokoll-Interceptor; Ping-Frames halten die Verbindung offen. */
    private val client: OkHttpClient = baseClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PROTOCOL_PING_SECONDS, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // ── Zustand unter [lock] ─────────────────────────────────────────────────────────────
    private val lock = Any()
    private val buffer = LiveBuffer()
    private var watchIds: Map<LiveExchange, Map<String, List<Long>>> = emptyMap()
    private val lastDataAt = HashMap<LiveExchange, Long>()
    private var marketNames: Map<String, String> = emptyMap()

    /** Laufende Verbindungen (nur aus der Steuer-Coroutine verändert). */
    private val connections = HashMap<String, Connection>()
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
        synchronized(lock) {
            watchIds = plan.watchIds
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
            if (key !in connections) connections[key] = Connection(spec).also { it.start() }
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
        // Zuletzt Empfangenes speichern, damit Widgets und Alarme den neuesten Stand haben
        withContext(NonCancellable) { flush(force = true) }
        synchronized(lock) {
            buffer.clear()
            watchIds = emptyMap()
            lastDataAt.clear()
        }
        coverage.clear()
        _prices.value = emptyMap()
        _liveExchanges.value = emptyList()
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
        val (snapshot, live) = synchronized(lock) {
            val names = lastDataAt.filterValues { now - it <= LiveRules.FRESH_MILLIS }.keys
                .mapNotNull { marketNames[it.marketKey] }
                .distinct()
                .sorted()
            buffer.takeForUi() to names
        }
        if (snapshot != null) _prices.value = snapshot
        if (_liveExchanges.value != live) _liveExchanges.value = live
    }

    private suspend fun flush(force: Boolean) {
        val batch = synchronized(lock) { buffer.takeForDb() }
        if (batch.isEmpty()) return
        val now = System.currentTimeMillis()
        val redraw = force || now - lastWidgetAt >= LiveRules.WIDGET_INTERVAL_MILLIS
        val saved = try {
            priceRefresher.applyLive(batch, redrawWidgets = redraw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Live-Kurse: Speichern fehlgeschlagen")
            false
        }
        if (saved) {
            if (redraw) lastWidgetAt = now
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

    /** Eine Verbindung mit eigener Schleife: verbinden, abonnieren, bei Abbruch neu verbinden. */
    private inner class Connection(val spec: LiveConnectionSpec) {
        private var job: Job? = null

        fun start() {
            job = scope.launch { run() }
        }

        fun stop() {
            job?.cancel()
        }

        private suspend fun run() {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                val state = refreshStats.backoffStates()[spec.exchange.marketKey]
                val now = System.currentTimeMillis()
                if (ExchangeBackoff.isPaused(state, now)) {
                    // Börse pausiert (zu viele Anfragen): bis dahin kein Verbindungsversuch
                    val until = state?.pausedUntil ?: now
                    delay((until - now).coerceIn(1_000L, LiveBackoff.RATE_LIMITED_MILLIS))
                    continue
                }
                val ended = connectOnce()
                attempt = if (ended.receivedData) 0 else attempt + 1
                delay(LiveBackoff.delayMillis(attempt, ended.rateLimited))
            }
        }

        private suspend fun connectOnce(): Ended = coroutineScope {
            val ended = CompletableDeferred<Ended>()
            val receivedData = AtomicBoolean(false)
            val lastMessageAt = AtomicLong(System.currentTimeMillis())
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastMessageAt.set(System.currentTimeMillis())
                    spec.exchange.subscribeMessages(spec.symbols).forEach { webSocket.send(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastMessageAt.set(System.currentTimeMillis())
                    if (onText(spec.exchange, text)) receivedData.set(true)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ended.complete(Ended(receivedData.get(), rateLimited = false))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    Timber.d(t, "Live-Kurse: %s getrennt (%s)", spec.exchange, code)
                    ended.complete(Ended(receivedData.get(), rateLimited = code == 429 || code == 418))
                }
            }
            val socket = client.newWebSocket(Request.Builder().url(spec.exchange.url).build(), listener)
            // Ping der Börse (Textnachricht) und Wächter: ohne Lebenszeichen neu verbinden
            val heartbeat = launch {
                var lastPingAt = System.currentTimeMillis()
                while (isActive) {
                    delay(HEARTBEAT_CHECK_MILLIS)
                    val now = System.currentTimeMillis()
                    val ping = spec.exchange.pingText
                    if (ping != null && now - lastPingAt >= spec.exchange.pingIntervalMillis) {
                        socket.send(ping)
                        lastPingAt = now
                    }
                    // Börsen mit Text-Ping antworten darauf (Pong) — bleibt alles aus, neu verbinden.
                    // Binance und Coinbase: Ping-Frames (pingInterval), ruhige Paare senden selten.
                    if (ping != null && now - lastMessageAt.get() > LiveRules.STALE_CONNECTION_MILLIS) {
                        socket.cancel()
                        break
                    }
                }
            }
            try {
                ended.await()
            } finally {
                heartbeat.cancel()
                // Abgebrochen (Merkliste zu, andere Gruppe) oder getrennt: Verbindung sicher zu
                if (!socket.close(NORMAL_CLOSURE, null)) socket.cancel()
            }
        }
    }

    private class Ended(val receivedData: Boolean, val rateLimited: Boolean)

    private companion object {
        /** WebSocket-Ping-Frames (OkHttp); bleibt die Antwort aus, gilt die Verbindung als getrennt. */
        const val PROTOCOL_PING_SECONDS = 20L
        const val HEARTBEAT_CHECK_MILLIS = 5_000L
        const val NORMAL_CLOSURE = 1000
    }
}
