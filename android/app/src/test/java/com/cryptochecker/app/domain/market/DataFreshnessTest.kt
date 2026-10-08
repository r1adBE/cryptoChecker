package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DataFreshnessTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun justNowUnderOneMinuteAndInFuture() {
        val now = at("2026-10-07T14:05:00")
        assertEquals(DataFreshness.Age.JustNow, DataFreshness.age(now, now, zone))
        assertEquals(DataFreshness.Age.JustNow, DataFreshness.age(now - minute + 1, now, zone))
        // Uhr verstellt: Zeitpunkt in der Zukunft
        assertEquals(DataFreshness.Age.JustNow, DataFreshness.age(now + 5 * minute, now, zone))
    }

    @Test
    fun minutesUnderOneHour() {
        val now = at("2026-10-07T14:05:00")
        assertEquals(DataFreshness.Age.Minutes(1), DataFreshness.age(now - minute, now, zone))
        assertEquals(DataFreshness.Age.Minutes(3), DataFreshness.age(now - 3 * minute - 59_000L, now, zone))
        assertEquals(DataFreshness.Age.Minutes(59), DataFreshness.age(now - hour + 1, now, zone))
        // Kurz nach Mitternacht: noch Minuten, nicht Datum
        val afterMidnight = at("2026-10-07T00:10:00")
        assertEquals(DataFreshness.Age.Minutes(20), DataFreshness.age(at("2026-10-06T23:50:00"), afterMidnight, zone))
    }

    @Test
    fun todayFromOneHourElseDate() {
        val now = at("2026-10-07T14:05:00")
        val twoAm = at("2026-10-07T02:00:00")
        assertEquals(DataFreshness.Age.Today(now - hour), DataFreshness.age(now - hour, now, zone))
        assertEquals(DataFreshness.Age.Today(twoAm), DataFreshness.age(twoAm, now, zone))
        val yesterday = at("2026-10-06T23:59:00")
        assertEquals(DataFreshness.Age.Date(yesterday), DataFreshness.age(yesterday, now, zone))
    }

    @Test
    fun staleAfterThreeTtl() {
        val now = at("2026-10-07T14:05:00")
        val ttl = CycleSource.GLOBAL.ttlMillis
        assertFalse(DataFreshness.isStale(now - 3 * ttl, now, ttl))
        assertTrue(DataFreshness.isStale(now - 3 * ttl - 1, now, ttl))
        assertFalse(DataFreshness.isStale(now + hour, now, ttl))
        assertFalse(DataFreshness.isStale(0L, now, 0L))
        // Gas (1 Min.): nach 3 Min. veraltet
        assertTrue(DataFreshness.isStale(now - 4 * minute, now, CycleSource.GAS.ttlMillis))
    }

    @Test
    fun providerNames() {
        assertEquals("Binance, Coin Metrics", DataFreshness.providers("Binance", null, " ", "Coin Metrics", "Binance"))
        assertNull(DataFreshness.providers(null, ""))
        assertEquals("Binance", DataFreshness.candleProvider("data-api.binance.vision"))
        assertEquals("Binance", DataFreshness.candleProvider("fapi.binance.com"))
        assertEquals("Binance.US", DataFreshness.candleProvider("api.binance.us"))
        assertEquals("Coinbase", DataFreshness.candleProvider("api.exchange.coinbase.com"))
        assertEquals("publicnode.com", DataFreshness.siteName("https://ethereum-rpc.publicnode.com"))
        assertEquals("llamarpc.com", DataFreshness.siteName("https://eth.llamarpc.com/"))
        assertEquals("cloudflare-eth.com", DataFreshness.siteName("https://cloudflare-eth.com"))
        assertEquals("mempool.space", DataFreshness.siteName("https://mempool.space/api/v1/fees/recommended"))
    }
}
