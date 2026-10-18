// HTTP der Börsen-Abfragen (Teil von `MarketRemoteDataSource.kt`, gespiegelt in `MarketHTTP.swift`): Wache gegen
// hängende Anfragen, Spiegel (DataMirror), gleiche gleichzeitige Anfragen nur einmal, Antwort mit Höchstgrösse und
// Drossel-Erkennung.
package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.CallGuard
import com.cryptochecker.app.data.remote.util.CallStart
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.InFlightRequests
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.MarketError
import com.cryptochecker.app.domain.exceptions.ResponseTooLargeError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.exceptions.parseMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.mirror.DataMirror
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.model.PostRequestInfo
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Kopie des Clients mit gleicher Gesamt- und Lesefrist. */
internal fun OkHttpClient.withTimeouts(seconds: Long): OkHttpClient =
    newBuilder()
        .callTimeout(seconds, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .build()

/**
 * Text einer Börsenanfrage (GET ohne, POST mit [postRequestInfo]). Fehler kommen als [MarketError].
 *
 * @param maxBytes Höchstgrösse der Antwort ([HttpBodyLimit]); Paar- und Sammellisten nehmen
 *   [HttpBodyLimit.BULK_MAX_BYTES].
 */
suspend fun OkHttpClient.callMarket(
    url: String,
    postRequestInfo: PostRequestInfo?,
    maxBytes: Long = HttpBodyLimit.DEFAULT_MAX_BYTES,
): String {
    // Wache gegen eine hängende OkHttp-Anfrage, knapp über dem callTimeout (gezählt ab der Ausführung, CallGuard)
    val guardMillis = if (callTimeoutMillis > 0) callTimeoutMillis + GUARD_EXTRA_MILLIS else GUARD_DEFAULT_MILLIS
    // Für alle gleiche Daten zuerst aus dem Spiegel auf GitHub (DataMirror), sonst beim Anbieter
    if (postRequestInfo == null) {
        val entry = DataMirror.entryFor(url)
        if (entry != null) fromMirror(entry, guardMillis, maxBytes)?.let { return it }
    }
    return request(url, postRequestInfo, maxBytes, guardMillis)
}

private const val GUARD_EXTRA_MILLIS = 5_000L
private const val GUARD_DEFAULT_MILLIS = 35_000L

/** Text aus dem Spiegel, wenn erreichbar und frisch genug; sonst null (dann direkt beim Anbieter). */
private suspend fun OkHttpClient.fromMirror(entry: DataMirror.Entry, guardMillis: Long, maxBytes: Long): String? =
    try {
        val text = request(entry.url, null, maxBytes, guardMillis)
        DataMirror.unwrap(text, System.currentTimeMillis(), entry.maxAgeMillis)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (ex: Exception) {
        Timber.d("Spiegel %s nicht erreichbar: %s", entry.file, ex.message)
        null
    }

/**
 * Eine Anfrage mit Wache ([CallGuard]). Gleiche gleichzeitige Anfragen laufen nur einmal ([InFlightRequests]): GETs
 * mit derselben Adresse, POSTs mit derselben Adresse, denselben Kopfzeilen und demselben Körper — etwa Hyperliquid,
 * dessen Einzelkurs für jedes Paar dieselbe «metaAndAssetCtxs»-Antwort (~100 KB) holt.
 */
private suspend fun OkHttpClient.request(
    url: String,
    postRequestInfo: PostRequestInfo?,
    maxBytes: Long,
    guardMillis: Long,
): String =
    try {
        val builder = Request.Builder().url(url)
        val key = if (postRequestInfo == null) {
            "$callTimeoutMillis|$maxBytes|$url"
        } else {
            postRequestInfo.headers?.forEach { (name, value) -> builder.addHeader(name, value) }
            builder.post(postRequestInfo.body.toRequestBody())
            "$callTimeoutMillis|$maxBytes|POST|$url|${postRequestInfo.headers?.toSortedMap()}|${postRequestInfo.body}"
        }
        val prepared = builder.build()
        InFlightRequests.shared(key) { execute(prepared, maxBytes, guardMillis) }
    } catch (ex: Exception) {
        ex.rethrowIfCritical()
        throw parseMarketError(ex)
    }

/**
 * Anfrage ausführen und lesen. Die Wache zählt erst ab dem Start beim Dispatcher ([CallStart]): Wer in der
 * Warteschlange wartet, endet nicht als Zeitüberschreitung. Abgelaufen → [TimeoutError] (gewöhnlicher Fehler der
 * Börse, nie eine TimeoutCancellationException — sonst rissen Teilanfragen, die scheitern dürfen, alles mit).
 */
private suspend fun OkHttpClient.execute(request: Request, maxBytes: Long, guardMillis: Long): String {
    val start = CallStart()
    val tagged = request.newBuilder().tag(CallStart::class.java, start).build()
    return CallGuard.run(start, guardMillis) { newCall(tagged).await().bodyText(maxBytes) }
        ?: throw TimeoutError(InterruptedIOException("timeout"))
}

/**
 * Body als Text (höchstens [maxBytes], sonst [ResponseTooLargeError] statt OutOfMemory), danach
 * geschlossen. Fehlercode oder erkannte Bremse → [HttpMarketError].
 */
private fun Response.bodyText(maxBytes: Long): String {
    val text = body.use { content ->
        HttpBodyLimit.read(content.byteStream(), content.contentLength(), maxBytes)?.let(HttpBodyLimit::decode)
    } ?: throw ResponseTooLargeError()
    // «Zu viele Anfragen» ohne 429/418 (Kraken 200, Bybit 403/10006, OKX 50011) als 429 melden
    val throttleCode = RateLimitSignal.httpCode(code, text)
    if (isSuccessful && throttleCode == null) return text
    throw HttpMarketError(
        throttleCode ?: code,
        text,
        // Bei Bremsung nennt die Börse oft die gewünschte Pause
        ExchangeBackoff.parseRetryAfterSeconds(header("Retry-After"), System.currentTimeMillis()),
    )
}
