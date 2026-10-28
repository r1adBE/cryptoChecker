// HTTP der Börsen-Abfragen (Teil von `ExchangeRemoteDataSource.kt`, gespiegelt in `ExchangeHTTP.swift`): Wache gegen
// hängende Anfragen, Spiegel (DataMirror), gleiche gleichzeitige Anfragen nur einmal, Antwort mit Höchstgrösse und
// Drossel-Erkennung.
package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.CallGuard
import com.cryptochecker.app.data.remote.util.CallStart
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.InFlightRequests
import com.cryptochecker.app.data.remote.util.RequestKey
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.exceptions.ExchangeError
import com.cryptochecker.app.domain.exceptions.ParseError
import com.cryptochecker.app.domain.exceptions.ResponseTooLargeError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.exceptions.parseExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.mirror.DataMirror
import com.cryptochecker.app.domain.mirror.MirrorManifest
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.model.PostRequest
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Kopie des Clients mit gleicher Gesamt- und Lesefrist. */
fun OkHttpClient.withTimeouts(seconds: Long): OkHttpClient =
    newBuilder()
        .callTimeout(seconds, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .build()

/**
 * Text einer Börsenanfrage (GET ohne, POST mit [postRequestInfo]). Fehler kommen als [ExchangeError].
 *
 * @param maxBytes Höchstgrösse der Antwort ([HttpBodyLimit]); Paar- und Sammellisten nehmen
 *   [HttpBodyLimit.BULK_MAX_BYTES].
 */
suspend fun OkHttpClient.callExchange(
    url: String,
    postRequestInfo: PostRequest?,
    maxBytes: Long = HttpBodyLimit.DEFAULT_MAX_BYTES,
): String {
    // Wache gegen eine hängende OkHttp-Anfrage, knapp über dem callTimeout (gezählt ab der Ausführung, CallGuard)
    val guardMillis = if (callTimeoutMillis > 0) callTimeoutMillis + GUARD_EXTRA_MILLIS else GUARD_DEFAULT_MILLIS
    // Für alle gleiche Daten zuerst aus dem Spiegel auf GitHub (DataMirror), sonst beim Anbieter
    if (postRequestInfo == null) {
        val entry = DataMirror.entryFor(url)
        if (entry != null) fromMirror(entry, guardMillis, maxBytes)?.let { return it }
    }
    val text = request(url, postRequestInfo, maxBytes, guardMillis)
    // Spiegel-Dateien direkt (Altcoin-Saison, coin_meta.json) nur mit gültig signiertem Manifest (MirrorManifest)
    if (postRequestInfo == null && MirrorManifest.isSignedFile(url) &&
        !verifiedMirror(url.removePrefix(DataMirror.BASE), text, guardMillis)
    ) {
        throw ParseError(IOException("Spiegel-Datei ohne gültige Signatur"))
    }
    return text
}

private const val GUARD_EXTRA_MILLIS = 5_000L
private const val GUARD_DEFAULT_MILLIS = 35_000L

/** Text aus dem Spiegel, wenn erreichbar, gültig signiert und frisch genug; sonst null (dann direkt beim Anbieter). */
private suspend fun OkHttpClient.fromMirror(entry: DataMirror.Entry, guardMillis: Long, maxBytes: Long): String? =
    try {
        val text = request(entry.url, null, maxBytes, guardMillis)
        if (verifiedMirror(entry.file, text, guardMillis)) {
            DataMirror.unwrap(text, System.currentTimeMillis(), entry.maxAgeMillis)
        } else {
            null
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (ex: Exception) {
        Timber.d("Spiegel %s nicht erreichbar: %s", entry.file, ex.message)
        null
    }

/** Signatur und SHA-256 der Spiegel-Datei prüfen ([MirrorTrust]); Manifest und Signatur über denselben Client. */
private suspend fun OkHttpClient.verifiedMirror(file: String, text: String, guardMillis: Long): Boolean =
    MirrorTrust.shared.verified(file, text, mirrorLoader(guardMillis))

/**
 * Lader für Manifest und Signatur mit eigener Höchstgrösse (zu gross → null wie jeder Fehler); ein Abbruch wird
 * weitergeworfen.
 */
fun OkHttpClient.mirrorLoader(guardMillis: Long): MirrorLoader = { url, limit ->
    try {
        request(url, null, limit, guardMillis)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (ex: Exception) {
        Timber.d("Spiegel-Manifest nicht erreichbar: %s", ex.message)
        null
    }
}

/**
 * Eine Anfrage mit Wache ([CallGuard]). Gleiche gleichzeitige Anfragen laufen nur einmal ([InFlightRequests]): GETs
 * mit derselben Adresse, POSTs mit derselben Adresse, denselben Kopfzeilen und demselben Körper — etwa Hyperliquid,
 * dessen Einzelkurs für jedes Paar dieselbe «metaAndAssetCtxs»-Antwort (~100 KB) holt.
 */
private suspend fun OkHttpClient.request(
    url: String,
    postRequestInfo: PostRequest?,
    maxBytes: Long,
    guardMillis: Long,
): String =
    try {
        val builder = Request.Builder().url(url)
        // Schlüssel ohne Zeitstempel (BingX, RequestKey): sonst wären gleiche Anfragen nie gleich
        val sameAs = RequestKey.of(url)
        val key = if (postRequestInfo == null) {
            "$callTimeoutMillis|$maxBytes|$sameAs"
        } else {
            postRequestInfo.headers?.forEach { (name, value) -> builder.addHeader(name, value) }
            builder.post(postRequestInfo.body.toRequestBody())
            "$callTimeoutMillis|$maxBytes|POST|$sameAs|${postRequestInfo.headers?.toSortedMap()}|${postRequestInfo.body}"
        }
        val prepared = builder.build()
        InFlightRequests.shared(key) { execute(prepared, maxBytes, guardMillis) }
    } catch (ex: Exception) {
        ex.rethrowIfCritical()
        throw parseExchangeError(ex)
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
 * geschlossen. Fehlercode oder erkannte Bremse → [HttpExchangeError].
 */
private fun Response.bodyText(maxBytes: Long): String {
    val text = body.use { content ->
        HttpBodyLimit.read(content.byteStream(), content.contentLength(), maxBytes)?.let(HttpBodyLimit::decode)
    } ?: throw ResponseTooLargeError()
    // Zu tief verschachtelt: vor jedem JSON-Parser abweisen (auch der Bremsen-Erkennung unten), sonst Stacküberlauf.
    // Fehlercode bleibt erhalten, nur ohne den Text.
    if (HttpBodyLimit.tooDeep(text)) {
        if (isSuccessful) throw ParseError(IOException("JSON zu tief verschachtelt"))
        throw HttpExchangeError(code, "", ExchangeBackoff.parseRetryAfterSeconds(header("Retry-After"), System.currentTimeMillis()))
    }
    // «Zu viele Anfragen» ohne 429/418 (Kraken 200, Bybit 403/10006, OKX 50011) als 429 melden
    val throttleCode = RateLimitSignal.httpCode(code, text)
    if (isSuccessful && throttleCode == null) return text
    throw HttpExchangeError(
        throttleCode ?: code,
        text,
        // Bei Bremsung nennt die Börse oft die gewünschte Pause
        ExchangeBackoff.parseRetryAfterSeconds(header("Retry-After"), System.currentTimeMillis()),
    )
}
