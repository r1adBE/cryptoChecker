package com.cryptochecker.app.data.remote.util

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.market.BingX
import com.cryptochecker.marketdata.model.market.BingXFutures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        for (market in listOf(BingX(), BingXFutures())) {
            val first = market.getBulkTickersUrl(0)
            Thread.sleep(2)
            val second = market.getBulkTickersUrl(0)
            // Echte Adressen verschieden (Stempel), Schlüssel gleich: kein zweiter identischer Abruf
            assertNotEquals(first, second)
            assertEquals(RequestKey.of(first), RequestKey.of(second))
            assertEquals(RequestKey.of(first), RequestKey.of(market.getBulkTickersUrl(0, listOf("BTC-USDT"))!!))
        }
        val info = PairQuery("BTC", "USDT", "BTC-USDT", FuturesContractType.NONE)
        val single = BingX().tickerAddress(0, info)
        Thread.sleep(2)
        assertEquals(RequestKey.of(single), RequestKey.of(BingX().tickerAddress(0, info)))
        assertEquals("BTC-USDT", RequestKey.of(single).substringAfter("symbol="))
    }
}
