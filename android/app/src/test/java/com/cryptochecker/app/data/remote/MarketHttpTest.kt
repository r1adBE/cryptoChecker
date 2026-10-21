package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.ParseError
import com.cryptochecker.app.domain.exceptions.ResponseTooLargeError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.app.domain.refresh.refreshFailure
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.BinanceFutures
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * HTTP-Schicht gegen einen echten lokalen Server (MockWebServer) statt Interceptor-Antworten: callMarket über
 * Call.await mit OkHttp-Fristen, Grössengrenze und «Retry-After»; Teilanfragen von [MarketRemoteDataSource]
 * (Einzelkurs, Paarliste, Sammelabfrage) mit einer kleinen Test-Börse, deren Adressen auf den Server zeigen.
 */
class MarketHttpTest {

    private lateinit var server: MockWebServer

    /** Adressen aller Anfragen, wie die App sie gestellt hat (vor einer Umleitung). */
    private val requested: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(callTimeoutMillis: Long = 5_000): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor(Interceptor { chain ->
            requested += chain.request().url.toString()
            chain.proceed(chain.request())
        })
        .build()

    /** Leitet Anfragen an feste Börsen-Hosts (z. B. Binance Futures) auf den Server um; Reihenfolge = Warteschlange. */
    private fun redirectingClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5_000, TimeUnit.MILLISECONDS)
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            requested += original.url.toString()
            val target = server.url("/" + original.url.host + original.url.encodedPath)
                .newBuilder().encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(target).build())
        })
        .build()

    private fun url(path: String): String = server.url(path).toString()

    private fun respond(code: Int = 200, body: String = "{}", retryAfter: String? = null) {
        val builder = MockResponse.Builder().code(code).body(body)
        if (retryAfter != null) builder.setHeader("Retry-After", retryAfter)
        server.enqueue(builder.build())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("${T::class.java.simpleName} erwartet, war ${e.javaClass.name}: ${e.message}", e)
        }
        fail("${T::class.java.simpleName} erwartet")
        throw IllegalStateException()
    }

    // ---- 429 und «Retry-After»

    @Test
    fun rateLimitWithRetryAfterSecondsBecomesRateLimitedAndPauses() = runBlocking {
        respond(429, retryAfter = "120")
        val e = expect<HttpMarketError> { runBlocking { client().callMarket(url("/ticker"), null) } }

        assertEquals(429, e.httpCode)
        assertEquals(120L, e.retryAfterSeconds)
        assertTrue(e.message.contains("retry-after 120 s"))
        val failure = e.toMarketFailure()
        assertEquals(MarketFailure.RateLimited(120L, 429), failure)

        // Pause der Börse wie nach einer Aktualisierung: «Retry-After» länger als die eigene erste Stufe (30 s)
        val now = 1_700_000_000_000L
        val outcome = ExchangeBackoff.outcome(listOfNotNull(failure.refreshFailure()), updated = 0)
        assertEquals(ExchangeBackoff.Outcome.RATE_LIMITED, outcome)
        val state = ExchangeBackoff.next(null, outcome, now, retryAfterMillis = ExchangeBackoff.retryAfterMillis(listOf(e.message)))
        assertNotNull(state)
        assertEquals(now + 120_000L, state!!.pausedUntil)
        assertTrue(ExchangeBackoff.isPaused(state, now + 119_000L))
        assertFalse(ExchangeBackoff.isPaused(state, now + 121_000L))
    }

    @Test
    fun retryAfterAsHttpDateIsRead() = runBlocking {
        val at = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(300)
        respond(429, retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(at))
        val e = expect<HttpMarketError> { runBlocking { client().callMarket(url("/ticker"), null) } }

        val seconds = e.retryAfterSeconds
        assertNotNull(seconds)
        assertTrue("etwa 300 s, war $seconds", seconds!! in 290L..300L)
        assertTrue(e.toMarketFailure() is MarketFailure.RateLimited)
    }

    @Test
    fun throttleTextWithStatus200CountsAsRateLimit() = runBlocking {
        // Kraken meldet die Bremse mit HTTP 200 und einem Fehlertext
        respond(200, body = """{"error":["EGeneral:Too many requests"]}""")
        val e = expect<HttpMarketError> { runBlocking { client().callMarket(url("/ticker"), null) } }
        assertEquals(429, e.httpCode)
        assertEquals(MarketFailure.RateLimited(null, 429), e.toMarketFailure())
    }

    @Test
    fun serverErrorKeepsItsStatus() = runBlocking {
        respond(503, body = "busy")
        val e = expect<HttpMarketError> { runBlocking { client().callMarket(url("/ticker"), null) } }
        assertEquals(MarketFailure.HttpStatus(503), e.toMarketFailure())
    }

    // ---- Grössengrenze

    @Test
    fun announcedBodyAboveTheLimitIsRejected() = runBlocking {
        respond(body = "x".repeat(4_096))
        expect<ResponseTooLargeError> { runBlocking { client().callMarket(url("/big"), null, maxBytes = 1_024) } }
        Unit
    }

    @Test
    fun chunkedBodyWithoutLengthStopsAtTheLimit() = runBlocking {
        // Ohne Content-Length: gelesen wird nur bis zur Grenze, nicht die ganze Antwort
        server.enqueue(MockResponse.Builder().code(200).chunkedBody("y".repeat(64 * 1_024), 1_024).build())
        expect<ResponseTooLargeError> { runBlocking { client().callMarket(url("/chunked"), null, maxBytes = 8_192) } }
        Unit
    }

    @Test
    fun defaultLimitRejectsAnOversizedSingleResponse() = runBlocking {
        respond(body = "z".repeat((HttpBodyLimit.DEFAULT_MAX_BYTES + 1).toInt()))
        expect<ResponseTooLargeError> { runBlocking { client().callMarket(url("/huge"), null) } }
        Unit
    }

    /** Tief verschachtelte Antwort: ParseError (gewöhnlicher Fehler) statt StackOverflowError beim Parsen. */
    @Test
    fun deeplyNestedBodyBecomesParseError() = runBlocking {
        respond(body = "[".repeat(100_000))
        expect<ParseError> { runBlocking { client().callMarket(url("/deep"), null) } }
        // Mit Fehlercode: Code bleibt, Text fällt weg
        respond(503, body = "{\"e\":" + "[".repeat(100_000))
        val e = expect<HttpMarketError> { runBlocking { client().callMarket(url("/deep2"), null) } }
        assertEquals(503, e.httpCode)
        assertEquals("", e.responseString)
    }

    @Test
    fun bodyAtTheLimitIsAccepted() = runBlocking {
        respond(body = "a".repeat(1_024))
        assertEquals(1_024, client().callMarket(url("/exact"), null, maxBytes = 1_024).length)
    }

    // ---- Fristen

    @Test
    fun hangingResponseEndsAsTimeoutErrorAfterCallTimeout() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(2, TimeUnit.SECONDS).build())
        val started = System.currentTimeMillis()
        val e = expect<Throwable> {
            runBlocking { withTimeout(10_000) { client(callTimeoutMillis = 300).callMarket(url("/slow"), null) } }
        }
        assertTrue("TimeoutError erwartet, war ${e.javaClass.name}", e is TimeoutError)
        assertFalse(e is TimeoutCancellationException)
        // Nicht erst an der Wache (callTimeout + 5 s)
        assertTrue(System.currentTimeMillis() - started < 1_800)
    }

    // ---- Teilanfragen

    /** Test-Börse: zwei Kursanfragen (Kurs, dann Volumen), zwei Paarlisten, zwei Sammelanfragen (Gruppe 0 und 1). */
    private class StubMarket(
        private val base: String,
        private val allCallsRequired: Boolean = false,
        private val onPairListRead: (() -> Unit)? = null,
    ) : Market("Stub", "Stub") {
        override val key: String = "Stub"

        override fun tickerCallCount(info: PairQuery?): Int = 2
        override fun tickerAddress(call: Int, info: PairQuery): String = "$base/ticker/$call"
        override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
            if (call == 0) ticker.last = json.getDouble("last") else ticker.vol = json.getDouble("vol")
        }

        override val pairListCallCount: Int get() = 2
        override val pairListAllCallsRequired: Boolean get() = allCallsRequired
        override fun pairListAddress(call: Int): String = "$base/pairs/$call"
        override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
            onPairListRead?.invoke()
            val rows = json.getJSONArray("pairs")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                out += MarketPair(row.getString(0), row.getString(1), row.getString(2))
            }
        }

        override val bulkTickersNumOfRequests: Int get() = 2
        override fun getBulkTickersUrl(call: Int): String = "$base/bulk/$call"
        override fun bulkTickersRequestGroup(call: Int): Int = call
        override fun bulkTickersPairGroup(pairId: String): Int = if (pairId.startsWith("ALPHA")) 1 else 0
        override fun parseBulkTickers(call: Int, body: String, tickers: MutableMap<String, Ticker>, failed: MutableSet<String>) {
            val rows = JSONArray(body)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                bulkRow(row.getString("id"), tickers, failed) { it.last = row.getDouble("last") }
            }
        }
    }

    private fun query() = PairQuery("BTC", "USDT", "BTCUSDT", FuturesContractType.NONE)

    @Test
    fun failedSecondTickerCallKeepsThePriceOfTheFirst() = runBlocking {
        respond(body = """{"last":101.5}""")
        respond(500, body = "volume down")
        val ticker = MarketRemoteDataSource(client()).fetchMarketTicker(StubMarket(url("").trimEnd('/')), query())
        assertEquals(101.5, ticker.last, 0.0)
        assertEquals(2, requested.size)
    }

    @Test
    fun hangingSecondTickerCallTimesOutAndKeepsThePrice() = runBlocking {
        respond(body = """{"last":101.5}""")
        server.enqueue(MockResponse.Builder().code(200).body("""{"vol":1}""").headersDelay(2, TimeUnit.SECONDS).build())
        val ticker = MarketRemoteDataSource(client(callTimeoutMillis = 300))
            .fetchMarketTicker(StubMarket(url("").trimEnd('/')), query())
        assertEquals(101.5, ticker.last, 0.0)
        assertEquals("Volumen fehlt", SimpleTicker().vol, ticker.vol, 0.0)
    }

    @Test
    fun failedFirstTickerCallFails() = runBlocking {
        respond(500, body = "down")
        val e = expect<HttpMarketError> {
            runBlocking { MarketRemoteDataSource(client()).fetchMarketTicker(StubMarket(url("").trimEnd('/')), query()) }
        }
        assertEquals(500, e.httpCode)
        assertEquals("ohne Kurs keine zweite Anfrage", 1, requested.size)
    }

    @Test
    fun zeroOrNonFinitePriceIsNoTickerDataAndConfirmsNotTraded() = runBlocking {
        // Binance-«BREAK»: Antwort mit lastPrice 0 — kein Kurs, kein Erfolg mit 0
        for (body in listOf("""{"last":0}""", """{"last":"0.00000000"}""", """{"last":-5}""", """{"last":"NaN"}""")) {
            respond(body = body)
            val e = expect<MarketParseException> {
                runBlocking { MarketRemoteDataSource(client()).fetchMarketTicker(StubMarket(url("").trimEnd('/')), query()) }
            }
            assertEquals(body, UserFriendlyMarketError.NO_TICKER_DATA, e.message)
            assertEquals(body, MarketFailure.Parse, e.toMarketFailure())
            assertTrue(body, NotTraded.confirmedBySingle(false, e.message, e.toMarketFailure()))
        }
        assertEquals("ohne Kurs keine zweite Anfrage", 4, requested.size)
    }

    @Test
    fun failedSecondPairListCallKeepsTheFirstList() = runBlocking {
        respond(body = """{"pairs":[["BTC","USDT","BTCUSDT"],["ETH","USDT","ETHUSDT"]]}""")
        respond(502, body = "bad gateway")
        val info = MarketRemoteDataSource(client()).fetchMarketCurrencyPairsInfo(StubMarket(url("").trimEnd('/')))
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), info.pairs.mapNotNull { it.pairId })
    }

    @Test
    fun pairListIsParsedOffTheCallersThread() = runBlocking {
        respond(body = """{"pairs":[["BTC","USDT","BTCUSDT"]]}""")
        respond(body = """{"pairs":[]}""")
        val caller = Thread.currentThread()
        val readOn = ArrayList<Thread>()
        val market = StubMarket(url("").trimEnd('/'), onPairListRead = { synchronized(readOn) { readOn += Thread.currentThread() } })
        MarketRemoteDataSource(client()).fetchMarketCurrencyPairsInfo(market)
        assertTrue("gelesen wurde", readOn.isNotEmpty())
        // Explorer ruft vom Haupt-Thread auf: Lesen darf nie im Kontext des Aufrufers laufen
        assertTrue(readOn.none { it === caller })
    }

    @Test
    fun allCallsRequiredFailsInsteadOfSavingHalfAList() = runBlocking {
        respond(body = """{"pairs":[["BTC","USDT","BTCUSDT"]]}""")
        respond(502, body = "bad gateway")
        val e = expect<HttpMarketError> {
            runBlocking {
                MarketRemoteDataSource(client()).fetchMarketCurrencyPairsInfo(StubMarket(url("").trimEnd('/'), allCallsRequired = true))
            }
        }
        assertEquals(502, e.httpCode)
    }

    @Test
    fun binanceFuturesWithoutCoinMKeepsTheOldList() = runBlocking {
        val usdM = """{"symbols":[{"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","contractType":"PERPETUAL","status":"TRADING"}]}"""
        val coinM = """{"symbols":[{"symbol":"BTCUSD_PERP","baseAsset":"BTC","quoteAsset":"USD","contractType":"PERPETUAL","contractStatus":"TRADING"}]}"""
        val source = MarketRemoteDataSource(redirectingClient())

        // USDⓈ-M (fapi) zuerst, COIN-M (dapi) fällt aus: Abgleich scheitert, die gespeicherte Liste bleibt
        respond(body = usdM)
        respond(503, body = "maintenance")
        val e = expect<HttpMarketError> { runBlocking { source.fetchMarketCurrencyPairsInfo(BinanceFutures()) } }
        assertEquals(503, e.httpCode)
        assertTrue(requested[0].contains("fapi"))
        assertTrue(requested[1].contains("dapi"))

        // Beide da: beide Hälften
        respond(body = usdM)
        respond(body = coinM)
        val info = source.fetchMarketCurrencyPairsInfo(BinanceFutures())
        assertEquals(listOf("2:BTCUSD_PERP", "BTCUSDT"), info.pairs.mapNotNull { it.pairId }.sorted())
    }

    @Test
    fun throttledSecondBulkCallKeepsFirstPartAndReportsThePause() = runBlocking {
        respond(body = """[{"id":"BTCUSDT","last":101.0}]""")
        respond(429, retryAfter = "45")
        val bulk = MarketRemoteDataSource(client())
            .fetchBulkTickers(StubMarket(url("").trimEnd('/')), listOf("BTCUSDT", "ALPHAUSDT"))

        assertEquals(101.0, bulk.tickers.getValue("BTCUSDT").last, 0.0)
        assertEquals(MarketFailure.RateLimited(45L, 429), bulk.failure)
        assertTrue(bulk.error!!.contains("retry-after 45 s"))
        // Nur die Gruppe der gebremsten Anfrage gilt als unvollständig
        assertEquals(setOf("ALPHAUSDT"), bulk.incomplete)
        assertEquals(45_000L, ExchangeBackoff.retryAfterMillis(listOf(bulk.error)))
    }

    @Test
    fun throttledFirstBulkCallFailsTheWholeQuery() = runBlocking {
        respond(429, retryAfter = "10")
        val e = expect<HttpMarketError> {
            runBlocking { MarketRemoteDataSource(client()).fetchBulkTickers(StubMarket(url("").trimEnd('/')), listOf("BTCUSDT")) }
        }
        assertEquals(429, e.httpCode)
        assertEquals("Rest weggelassen", 1, requested.size)
    }
}
