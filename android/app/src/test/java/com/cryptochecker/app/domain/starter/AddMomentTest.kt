package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMomentTest {

    private fun added(base: String, quote: String = "USDT", market: String = "Binance") =
        AddMoment.Added(market, base, quote, "$base/$quote")

    @Test
    fun bannerSubject() {
        assertEquals("", AddMoment.subject(emptyList()))
        assertEquals("BTC/USDT", AddMoment.subject(listOf(added("BTC"))))
        assertEquals(
            "BTC, ETH, XRP, BNB, SOL",
            AddMoment.subject(listOf("BTC", "ETH", "XRP", "BNB", "SOL").map { added(it) })
        )
    }

    @Test
    fun matchesRowsByMarketBaseAndQuote() {
        val m = AddMoment(1L, listOf(added("BTC"), added("ETH")))
        assertEquals(0, m.indexOf("Binance", "btc", "usdt"))
        assertEquals(1, m.indexOf("Binance", "ETH", "USDT"))
        assertNull(m.indexOf("Coinbase", "BTC", "USDT"))
        assertNull(m.indexOf("Binance", "BTC", "USDC"))
        assertNull(m.indexOf("Binance", "SOL", "USDT"))
    }

    @Test
    fun staggerAndDuration() {
        assertEquals(0L, AddMoment.staggerDelay(0))
        assertEquals(360L, AddMoment.staggerDelay(4))
        assertEquals(0L, AddMoment.staggerDelay(-1))
        // Ein Paar: Ablauf im Rahmen; fünf Paare dauern nur um die Staffelung länger
        assertTrue(AddMoment.totalMillis(5) - AddMoment.totalMillis(1) == 360L)
        assertTrue(AddMoment.totalMillis(1) > AddMoment.ENTER_MILLIS + AddMoment.CHECK_HOLD_MILLIS)
    }
}
