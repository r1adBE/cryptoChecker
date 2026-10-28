package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.exchange.Kraken
import com.cryptochecker.marketdata.model.exchange.OneTrading
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Ausweg des Einzelkurses nach einem Serverfehler ([SingleTickerFallback]): die Fälle `singleFallback` aus
 * `testdata/exchanges/exchange_onetrading.json` (wie iOS `SingleTickerFallbackTests.swift`), dazu der ganze Weg
 * über [ExchangeRemoteDataSource] mit einem Interceptor als Börse — 200 unverändert, 500 → Sammelliste, Paar fehlt →
 * der Serverfehler, 404 ohne Ausweg.
 */
class SingleTickerFallbackTest {

    private val single = "https://api.onetrading.com/fast/v1/market-ticker/BTC_EUR"
    private val bulk = "https://api.onetrading.com/fast/v1/market-ticker"
    private val btcEur = PairQuery("BTC", "EUR", "BTC_EUR", FuturesContractType.NONE)

    private fun fixtureCases(): List<Map<String, Any?>> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "testdata/exchanges").isDirectory) dir = dir.parentFile
        val file = File(dir ?: throw IllegalStateException("testdata/exchanges nicht gefunden"),
            "testdata/exchanges/exchange_onetrading.json")
        return ParityJson.parse(file.readText(Charsets.UTF_8)).obj()["cases"].list().map { it.obj() }
            .filter { it["kind"] == "singleFallback" }
    }

    @Test
    fun fixtureCasesThroughTheFallback() = runBlocking {
        val cases = fixtureCases()
        assertTrue("singleFallback-Fälle: ${cases.size}", cases.size >= 5)
        for (c in cases) {
            val name = c["name"] as String
            val spec = c["pair"].obj()
            val info = PairQuery(spec["base"] as String, spec["quote"] as String, spec["id"] as String, FuturesContractType.NONE)
            val status = c["status"].num()!!.toInt()
            assertTrue("$name: status $status ist ein Serverfehler", SingleTickerFallback.applies(status))
            val server = HttpExchangeError(status, "")
            val expect = c["expect"].obj()
            val ticker = SimpleTicker()
            var requested: String? = null
            try {
                SingleTickerFallback.load(OneTrading(), 0, info, ticker, server) { url ->
                    requested = url
                    c["body"] as String
                }
                assertFalse("$name: Fehler erwartet, Kurs ${ticker.last}", expect["error"] == true)
                assertEquals(name, expect["last"].num()!!, ticker.last, 1e-9)
                expect["change24hPercent"].num()?.let { assertEquals(name, it, ticker.change24hPercent!!, 1e-9) }
            } catch (e: HttpExchangeError) {
                assertTrue("$name: unerwarteter Fehler $e", expect["error"] == true)
                // Der ursprüngliche Serverfehler, kein eigener Text
                assertSame(name, server, e)
            }
            assertEquals(name, bulk, requested)
        }
    }

    @Test
    fun clientErrorsAndRateLimitsHaveNoFallback() = runBlocking {
        for (code in listOf(400, 404, 418, 429, 499, 600)) {
            assertFalse("$code", SingleTickerFallback.applies(code))
            val server = HttpExchangeError(code, "")
            var loaded = false
            try {
                SingleTickerFallback.load(OneTrading(), 0, btcEur, SimpleTicker(), server) { loaded = true; "[]" }
                fail("$code: Fehler erwartet")
            } catch (e: HttpExchangeError) {
                assertSame(server, e)
            }
            assertFalse("$code: keine Ausweichanfrage", loaded)
        }
    }

    @Test
    fun exchangeWithoutFallbackAddressKeepsTheServerError() = runBlocking {
        val server = HttpExchangeError(500, "")
        var loaded = false
        try {
            SingleTickerFallback.load(Kraken(), 0, btcEur, SimpleTicker(), server) { loaded = true; "{}" }
            fail("Fehler erwartet")
        } catch (e: HttpExchangeError) {
            assertSame(server, e)
        }
        assertFalse(loaded)
    }

    @Test
    fun failingFallbackRequestKeepsTheServerError() = runBlocking {
        val server = HttpExchangeError(502, "")
        try {
            SingleTickerFallback.load(OneTrading(), 0, btcEur, SimpleTicker(), server) { throw IOException("offline") }
            fail("Fehler erwartet")
        } catch (e: HttpExchangeError) {
            assertSame(server, e)
        }
    }

    @Test
    fun rateLimitOfTheFallbackPassesThrough() = runBlocking {
        for (code in listOf(429, 418)) {
            val limited = HttpExchangeError(code, "")
            try {
                SingleTickerFallback.load(OneTrading(), 0, btcEur, SimpleTicker(), HttpExchangeError(500, "")) {
                    throw limited
                }
                fail("Fehler erwartet")
            } catch (e: HttpExchangeError) {
                assertSame("$code: Bremse des Auswegs geht durch", limited, e)
            }
        }
    }

    // ── Ganzer Weg über ExchangeRemoteDataSource ──

    private val hits = HashMap<String, Int>()

    private fun source(replies: Map<String, Pair<Int, String>>) = ExchangeRemoteDataSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits[url] = (hits[url] ?: 0) + 1
            val (code, body) = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("status $code")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    private fun bulkBody(): String = fixtureCases().first { it["expect"].obj()["error"] != true }["body"] as String

    @Test
    fun singleTickerOkIsUnchanged() = runBlocking {
        val body = """{"instrument_code":"BTC_EUR","last_price":"61000.5","price_change_percentage":"1.5"}"""
        val ticker = source(mapOf(single to (200 to body), bulk to (200 to bulkBody()))).fetchExchangeTicker(OneTrading(), btcEur)
        assertEquals(61000.5, ticker.last, 1e-9)
        assertEquals(null, hits[bulk])
    }

    @Test
    fun serverErrorTakesThePriceFromTheBulkList() = runBlocking {
        val ticker = source(mapOf(single to (500 to "Internal Server Error"), bulk to (200 to bulkBody())))
            .fetchExchangeTicker(OneTrading(), btcEur)
        assertEquals(58000.1, ticker.last, 1e-9)
        assertEquals(1, hits[bulk])
    }

    @Test
    fun pairMissingFromTheBulkListKeepsTheServerError() = runBlocking {
        val foo = PairQuery("FOO", "EUR", "FOO_EUR", FuturesContractType.NONE)
        val fooUrl = "https://api.onetrading.com/fast/v1/market-ticker/FOO_EUR"
        try {
            source(mapOf(fooUrl to (500 to "Internal Server Error"), bulk to (200 to bulkBody()))).fetchExchangeTicker(OneTrading(), foo)
            fail("Fehler erwartet")
        } catch (e: HttpExchangeError) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(1, hits[bulk])
    }

    @Test
    fun notFoundHasNoFallback() = runBlocking {
        try {
            source(mapOf(single to (404 to """{"error":"INSTRUMENT_NOT_FOUND"}"""), bulk to (200 to bulkBody())))
                .fetchExchangeTicker(OneTrading(), btcEur)
            fail("Fehler erwartet")
        } catch (e: HttpExchangeError) {
            assertEquals(404, e.httpCode)
        }
        assertEquals(null, hits[bulk])
    }
}
