package com.cryptochecker.app.data.remote

import com.cryptochecker.marketdata.model.PostRequestInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Gleiche gleichzeitige POST-Abfragen gehen nur einmal ins Netz (InFlightRequests), etwa Hyperliquid
 * «metaAndAssetCtxs» (~100 KB, für jedes Paar dieselbe Antwort). Andere Körper bleiben getrennt.
 * Wie `MarketHTTPSharedPostTests`.
 */
class MarketHttpSharedPostTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    private fun delayed(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).headersDelay(300, TimeUnit.MILLISECONDS).build())
    }

    @Test
    fun identicalConcurrentPostsShareOneRequest() = runBlocking {
        delayed("[1]")
        val url = server.url("/info").toString()
        val post = PostRequestInfo("""{"type":"metaAndAssetCtxs"}""", mapOf("Content-Type" to "application/json"))
        val bodies = (1..3).map { async(Dispatchers.IO) { client.callMarket(url, post) } }.awaitAll()
        assertEquals(listOf("[1]", "[1]", "[1]"), bodies)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun differentBodiesStaySeparate() = runBlocking {
        delayed("[2]")
        delayed("[3]")
        val url = server.url("/info").toString()
        val bodies = listOf("""{"type":"meta"}""", """{"type":"allMids"}""")
            .map { body -> async(Dispatchers.IO) { client.callMarket(url, PostRequestInfo(body)) } }
            .awaitAll()
        assertEquals(setOf("[2]", "[3]"), bodies.toSet())
        assertEquals(2, server.requestCount)
    }
}
