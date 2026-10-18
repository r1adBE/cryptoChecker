// WebSocket-Verbindung einer Börse (Teil von `LivePriceStream`): verbinden, abonnieren, Ping und Wächter, bei
// Abbruch mit wachsender Pause neu verbinden. Puffer und Zustand bleiben im Strom ([onText]).
package com.cryptochecker.app.data.live

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.live.LiveBackoff
import com.cryptochecker.app.domain.live.LiveConnectionSpec
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Eine Verbindung mit eigener Schleife: verbinden, abonnieren, bei Abbruch neu verbinden (für [LivePriceStream]). */
internal class LiveConnection(
    val spec: LiveConnectionSpec,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val refreshStats: RefreshStats,
    /** Nachricht einer Börse verarbeiten; true = mindestens ein Kurs für ein sichtbares Paar. */
    private val onText: (LiveExchange, String) -> Boolean,
) {
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

    private class Ended(val receivedData: Boolean, val rateLimited: Boolean)

    private companion object {
        const val HEARTBEAT_CHECK_MILLIS = 5_000L
        const val NORMAL_CLOSURE = 1000
    }
}
