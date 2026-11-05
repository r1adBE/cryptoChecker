package com.cryptochecker.app.data.remote

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.NbpReference
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Referenzkurse aus mehreren Antworten ([MultiResponses], NBP: Tabelle A und B): eine Anfrage je Adresse, egal wie viele
 * Paare und ob Einzel- oder Sammelkurs; behalten bis zur nächsten Veröffentlichung (mindestens eine Stunde). Gegen einen
 * lokalen Server; die Adressen von api.nbp.pl werden dorthin umgeleitet. Wie iOS `MultiResponsesTests`.
 */
class MultiResponsesTest {

    private lateinit var server: MockWebServer
    private val requested: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        MultiResponses.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        MultiResponses.clear()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5_000, TimeUnit.MILLISECONDS)
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            requested += original.url.toString()
            val target = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(target).build())
        })
        .build()

    private fun respond(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    @Test
    fun oneRequestPerTableForBulkAndSingle() = runBlocking {
        respond(TABLE_A)
        respond(TABLE_B)
        val source = ExchangeRemoteDataSource(client())
        val exchange = NbpReference()

        val first = source.fetchBulkTickers(exchange, listOf("NGNUSD", "NGNEUR"))
        val second = source.fetchBulkTickers(exchange, listOf("NGNUSD"))
        val single = source.fetchExchangeTicker(exchange, PairQuery("NGN", "EUR", "NGNEUR", FuturesContractType.NONE))

        assertEquals("Tabelle A und B je einmal", 2, requested.size)
        assertTrue(requested[0].contains("/tables/A/last/15"))
        assertTrue(requested[1].contains("/tables/B/last/2"))
        // 0.00291 / 3.9097 und 0.002951 / 3.9624, je 5 Stellen
        assertEquals(0.0007443, first.tickers.getValue("NGNUSD").last, 1e-12)
        assertEquals(first.tickers.getValue("NGNUSD").last, second.tickers.getValue("NGNUSD").last, 0.0)
        assertEquals(0.00066115, single.last, 1e-12)
    }

    private companion object {
        const val TABLE_A = """[{"table":"A","effectiveDate":"2026-10-28","rates":[{"code":"USD","mid":3.9624},""" +
            """{"code":"EUR","mid":4.409}]},{"table":"A","effectiveDate":"2026-11-04","rates":[{"code":"USD","mid":3.9097},""" +
            """{"code":"EUR","mid":4.4014}]}]"""
        const val TABLE_B = """[{"table":"B","effectiveDate":"2026-10-28","rates":[{"code":"NGN","mid":0.002951}]},""" +
            """{"table":"B","effectiveDate":"2026-11-04","rates":[{"code":"NGN","mid":0.00291}]}]"""
    }
}
