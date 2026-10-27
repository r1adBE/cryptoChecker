// Eigener OkHttp-Dispatcher für den Live-Strom (Teil von `LivePriceStream`).
// Eine offene WebSocket-Verbindung belegt ihren Platz im Dispatcher, solange sie läuft (OkHttp liest im Rückruf des
// Handshakes). Im gemeinsamen Dispatcher (32 gesamt, 5 je Host) blieben so bis 24 Plätze dauerhaft belegt und die
// REST-Abfragen derselben Börse stauten sich – OKX und OKX-Swap bzw. Bybit Spot und Linear teilen sogar einen Host.
package com.cryptochecker.app.data.live

import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LivePlanner
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

object LiveDispatcher {

    /** Höchstens so viele Live-Verbindungen gleichzeitig: jede Börse mit allen erlaubten Verbindungen. */
    val maxRequests: Int = LiveExchange.entries.size * LivePlanner.MAX_CONNECTIONS_PER_EXCHANGE

    /** Je Host: so viele Börsen sich einen Host teilen, je mit allen erlaubten Verbindungen. */
    val maxRequestsPerHost: Int = LiveExchange.entries
        .groupingBy { exchange -> URI(exchange.url).host }
        .eachCount()
        .values
        .max() * LivePlanner.MAX_CONNECTIONS_PER_EXCHANGE

    fun create(): Dispatcher = Dispatcher().apply {
        maxRequests = this@LiveDispatcher.maxRequests
        maxRequestsPerHost = this@LiveDispatcher.maxRequestsPerHost
    }

    /**
     * Client des Live-Stroms aus dem gemeinsamen [base] (Verbindungs-Pool, Fristen fürs Verbinden): ohne Gesamt-
     * Zeitgrenze und Protokoll-Interceptor, Ping-Frames alle [pingSeconds] halten die Verbindung offen. Der User-Agent
     * bleibt (Handshake wie REST); eigener Dispatcher ([create]).
     */
    fun client(base: OkHttpClient, pingSeconds: Long): OkHttpClient = base.newBuilder()
        .apply {
            val userAgent = interceptors().filterIsInstance<UserAgentInterceptor>()
            interceptors().clear()
            networkInterceptors().clear()
            interceptors().addAll(userAgent)
        }
        .dispatcher(create())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(pingSeconds, TimeUnit.SECONDS)
        .build()
}
