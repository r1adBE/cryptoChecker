package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wie `AutoOrderTests.swift` (iOS): dieselben Paare, dieselben Erwartungen. */
class AutoOrderTest {

    private data class Row(val id: Int, val name: String, val exchange: String, val fav: Boolean = false)

    // eigene Reihenfolge
    private val rows = listOf(
        Row(1, "ETH/USDT", "Kraken"),
        Row(2, "btc/USDT", "Binance"),
        Row(3, "BTC/USDT Perp", "Bybit"),
        Row(4, "BTC/USDT", "bitstamp"),
        Row(5, "SOL/CHF", "Binance", fav = true),
        Row(6, "ADA/USDT", "Kraken", fav = true),
        Row(7, "ETH/USDT", "Kraken"),
        Row(8, "eth/usdt", "Binance"),
    )

    private fun ids(order: AutoOrder, items: List<Row> = rows) =
        order.apply(items, { it.fav }, { it.name }, { it.exchange }).map { it.id }

    @Test
    fun coinPutsSameCoinsTogether() {
        // Favoriten zuerst (A–Z), dann Name A–Z, bei gleichem Namen Börse A–Z, sonst bisherige Reihenfolge
        assertEquals(listOf(6, 5, 2, 4, 3, 8, 1, 7), ids(AutoOrder.COIN))
    }

    @Test
    fun exchangeGroupsByExchange() {
        // Favoriten nach Börse (Binance vor Kraken), dann Binance, bitstamp, Bybit, Kraken — je Name A–Z
        assertEquals(listOf(5, 6, 2, 8, 4, 3, 1, 7), ids(AutoOrder.EXCHANGE))
    }

    @Test
    fun emptyAndSingleStayUnchanged() {
        assertEquals(emptyList<Int>(), ids(AutoOrder.COIN, emptyList()))
        assertEquals(listOf(5), ids(AutoOrder.EXCHANGE, listOf(rows[4])))
    }

    @Test
    fun orderingTwiceChangesNothing() {
        val once = AutoOrder.COIN.apply(rows, { it.fav }, { it.name }, { it.exchange })
        assertEquals(once.map { it.id }, ids(AutoOrder.COIN, once))
    }
}
