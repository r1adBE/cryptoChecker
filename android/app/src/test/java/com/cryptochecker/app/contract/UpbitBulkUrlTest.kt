package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.exchange.Bithumb
import com.cryptochecker.marketdata.model.exchange.Upbit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upbit: viele Paare auf mehrere gefilterte URLs verteilt (sonst HTTP 414). Gegenstück: `Tests/UpbitBulkUrlTests.swift`. */
class UpbitBulkUrlTest {

    private val many = (1..400).map { "KRW-C%03d".format(it) }

    @Test
    fun longListsAreSplit() {
        val exchange = Upbit()
        val count = exchange.bulkTickersRequestCount(many)
        assertTrue(count > 1)
        val urls = (0 until count).map { exchange.getBulkTickersUrl(it, many)!! }
        assertTrue(urls.all { it.length < 2000 && it.startsWith("https://api.upbit.com/v1/ticker?markets=") })
        assertEquals(many.toSet(), urls.flatMap { it.substringAfter("markets=").split(",") }.toSet())
        assertNull(exchange.getBulkTickersUrl(count, many))
        // Wenige Paare: eine Anfrage wie bisher
        assertEquals(1, exchange.bulkTickersRequestCount(listOf("KRW-ETH", "KRW-BTC")))
        assertEquals("https://api.upbit.com/v1/ticker?markets=KRW-BTC,KRW-ETH", exchange.getBulkTickersUrl(0, listOf("KRW-ETH", "KRW-BTC")))
        // Bithumb (ohne ungefilterten Ersatz) bleibt bei einer Anfrage
        assertEquals(1, Bithumb().bulkTickersRequestCount(many))
    }
}
