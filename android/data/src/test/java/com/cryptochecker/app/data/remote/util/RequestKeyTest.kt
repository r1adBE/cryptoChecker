package com.cryptochecker.app.data.remote.util

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.BingX
import com.cryptochecker.marketdata.model.exchange.BingXFutures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Vergleichsschlüssel ohne `timestamp` (`request_key.json`, geteilt mit iOS). Wie `RequestKeyTests.swift`. */
class RequestKeyTest {

    @Test
    fun parityCases() {
        @Suppress("UNCHECKED_CAST")
        val cases = ParityJson.load("request_key.json")["cases"] as List<Map<String, Any?>>
        for (c in cases) assertEquals(c["url"] as String, c["expected"], RequestKey.of(c["url"] as String))
    }

    @Test
    fun bingxStampedAddressesCompareEqual() {
        for (exchange in listOf(BingX(), BingXFutures())) {
            val first = exchange.getBulkTickersUrl(0)
            // Dieselbe Abfrage eine Millisekunde später (fester Stempel statt Warten auf die Uhr)
            val second = restamped(first)
            // Echte Adressen verschieden (Stempel), Schlüssel gleich: kein zweiter identischer Abruf
            assertNotEquals(first, second)
            assertEquals(RequestKey.of(first), RequestKey.of(second))
            assertEquals(RequestKey.of(first), RequestKey.of(exchange.getBulkTickersUrl(0, listOf("BTC-USDT"))!!))
        }
        val info = PairQuery("BTC", "USDT", "BTC-USDT", FuturesContractType.NONE)
        val single = BingX().tickerAddress(0, info)
        assertEquals(RequestKey.of(single), RequestKey.of(restamped(single)))
        assertEquals("BTC-USDT", RequestKey.of(single).substringAfter("symbol="))
    }

    /** [url] mit dem Stempel `timestamp` + 1 ms (die Adresse muss einen tragen). */
    private fun restamped(url: String): String {
        val stamp = Regex("timestamp=(\\d+)").find(url)
        assertNotNull("kein timestamp in $url", stamp)
        val value = stamp!!.groupValues[1].toLong()
        return url.replace("timestamp=$value", "timestamp=${value + 1}")
    }
}
