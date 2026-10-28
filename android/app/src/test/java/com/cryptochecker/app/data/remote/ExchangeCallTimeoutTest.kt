package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.marketdata.model.exchange.BinanceFutures
import com.cryptochecker.marketdata.model.exchange.Kraken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Netzschicht ohne Netz: Ein Interceptor spielt die Börse. Geprüft werden die Zeitgrenzen (OkHttp-callTimeout
 * kommt als [TimeoutError] an, eine innere Frist reisst nichts mit) und die Paarliste von Binance Futures.
 */
class ExchangeCallTimeoutTest {

    private fun client(callTimeoutMillis: Long = 0, reply: (String) -> Response?) = OkHttpClient.Builder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            reply(url)?.newBuilder()?.request(request)?.build() ?: throw IOException("offline: $url")
        })
        .build()

    private fun ok(body: String): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url("https://example.invalid/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun okHttpCallTimeoutArrivesAsTimeoutError() = runBlocking {
        // Antwort kommt erst nach dem callTimeout: OkHttp bricht selbst ab (call.isCanceled() == true)
        val slow = client(callTimeoutMillis = 100) { Thread.sleep(600); ok("{}") }
        val started = System.currentTimeMillis()
        try {
            withTimeout(4_000) { slow.callExchange("https://example.invalid/slow", null) }
            fail("Zeitüberschreitung erwartet")
        } catch (e: TimeoutError) {
            // Nicht erst bei der Wache (callTimeout + 5 s)
            assertTrue(System.currentTimeMillis() - started < 4_000)
        }
    }

    @Test
    fun innerTimeoutIsNotCritical() = runBlocking {
        var handled = false
        try {
            withTimeout(10) { awaitCancellation() }
        } catch (e: Exception) {
            // Eigene Coroutine läuft noch: gewöhnlicher Fehler, kein Weiterwerfen
            e.rethrowIfCritical()
            handled = true
        }
        // Zeitüberschreitung kam an und wurde als gewöhnlicher Fehler behandelt (nicht weitergeworfen)
        assertTrue(handled)
        assertTrue(isActive)
    }

    @Test
    fun ownCancellationIsStillRethrown() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var reachedAfter = false
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: Exception) {
                e.rethrowIfCritical()
            }
            reachedAfter = true
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(reachedAfter)
    }

    @Test
    fun binanceFuturesNeedsBothPairLists() {
        assertTrue(BinanceFutures().pairListAllCallsRequired)
        assertFalse(Kraken().pairListAllCallsRequired)
    }

    @Test
    fun failedCoinMListKeepsTheOldList() = runBlocking {
        val usdM = """{"symbols":[{"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","contractType":"PERPETUAL","status":"TRADING"}]}"""
        val coinM = """{"symbols":[{"symbol":"BTCUSD_PERP","baseAsset":"BTC","quoteAsset":"USD","contractType":"PERPETUAL","contractStatus":"TRADING"}]}"""

        // dapi fällt aus: Abgleich scheitert (die gespeicherte Liste bleibt), statt nur USDⓈ-M zu speichern
        val dapiDown = ExchangeRemoteDataSource(client { url -> if (url.contains("fapi")) ok(usdM) else null })
        try {
            dapiDown.fetchExchangeCurrencyPairsInfo(BinanceFutures())
            fail("Abgleich muss scheitern")
        } catch (expected: Exception) {
            // gewöhnlicher Börsenfehler
        }

        val bothUp = ExchangeRemoteDataSource(client { url -> ok(if (url.contains("fapi")) usdM else coinM) })
        val info = bothUp.fetchExchangeCurrencyPairsInfo(BinanceFutures())
        assertEquals(listOf("2:BTCUSD_PERP", "BTCUSDT"), info.pairs.mapNotNull { it.pairId }.sorted())
    }
}
