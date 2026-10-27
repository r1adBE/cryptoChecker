package com.cryptochecker.app.data.remote.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Meldet, wann OkHttp eine Anfrage tatsächlich ausführt. Wartet sie in der Warteschlange des Dispatchers (höchstens
 * `maxRequestsPerHost` gleichzeitig je Host), zählt das nicht zur Frist der Wache ([CallGuard]) — sonst endeten
 * wartende Anfragen als Schein-Zeitüberschreitung und pausierten die Börse (ExchangeBackoff). Der callTimeout von
 * OkHttp beginnt ebenfalls erst mit der Ausführung.
 */
class CallStart {
    private val started = CompletableDeferred<Unit>()

    fun markStarted() {
        started.complete(Unit)
    }

    suspend fun await() = started.await()
}

/** Application-Interceptor: Die Kette läuft erst, wenn der Dispatcher die Anfrage startet ([CallStart]). */
object CallStartInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        chain.request().tag(CallStart::class.java)?.markStarted()
        return chain.proceed(chain.request())
    }
}

/**
 * Wache gegen eine hängende OkHttp-Anfrage: [block] darf ab dem Start der Ausführung ([CallStart]) höchstens
 * [guardMillis] dauern, sonst wird er abgebrochen und das Ergebnis ist null (gewöhnliche Zeitüberschreitung, nie
 * eine TimeoutCancellationException, die wie ein Abbruch des ganzen Durchlaufs aussähe). Meldet der Client den
 * Start nicht (ohne [CallStartInterceptor]), beginnt die Frist spätestens nach [queueFallbackMillis].
 */
object CallGuard {
    /** Längste Wartezeit in der Warteschlange, wenn der Client den Start nicht meldet. */
    const val QUEUE_FALLBACK_MILLIS = 60_000L

    suspend fun <T> run(
        start: CallStart,
        guardMillis: Long,
        queueFallbackMillis: Long = QUEUE_FALLBACK_MILLIS,
        block: suspend () -> T,
    ): T? = coroutineScope {
        val work = async { block() }
        val watchdog = launch {
            withTimeoutOrNull(queueFallbackMillis) { start.await() }
            delay(guardMillis)
            work.cancel(GuardExpired())
        }
        try {
            work.await()
        } catch (expired: GuardExpired) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    /** Abbruch durch die Wache (nur die Anfrage, nicht der Aufrufer). */
    private class GuardExpired : CancellationException("call guard expired")
}
