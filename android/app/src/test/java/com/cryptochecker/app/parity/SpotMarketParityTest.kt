package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.mirror.DataMirror
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.domain.spot.SpotMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Gemeinsame Testfälle `spot_market.json` (iOS: `SpotMarketTests.swift`). */
class SpotMarketParityTest {

    private val data = ParityJson.load("spot_market.json")
    private val us = Locale.US

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = (data[key] as List<Any?>).map { it as Map<String, Any?> }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun assertNum(message: String, expected: Any?, actual: Double?) {
        val e = num(expected)
        if (e == null) {
            assertNull(message, actual)
        } else {
            assertNotNull(message, actual)
            assertEquals(message, e, actual ?: Double.NaN, 1e-9 * maxOf(1.0, kotlin.math.abs(e)))
        }
    }

    @Test
    fun constants() {
        assertEquals(data["topUrl"], SpotMarket.TOP_URL)
        assertEquals(num(data["cacheTtlMillis"])?.toLong(), SpotMarket.CACHE_TTL_MILLIS)
        // CoinGecko-Daten nie über den Spiegel (API-Bedingungen): direkt beim Anbieter
        assertNull(DataMirror.entryFor(SpotMarket.TOP_URL))
    }

    @Test
    fun parse() {
        val parsed = SpotMarket.parse(data["body"] as String)
        val expected = list("parsed")
        assertEquals(expected.size, parsed.size)
        expected.zip(parsed).forEach { (e, a) -> assertEntry(e, a) }
        (data["parseInvalid"] as List<*>).forEach { body ->
            assertTrue("invalid: $body", SpotMarket.parse(body as String?).isEmpty())
        }
    }

    private fun assertEntry(e: Map<String, Any?>, a: SpotData) {
        val m = "entry ${e["id"]}"
        assertEquals(m, e["id"], a.id)
        assertEquals(m, e["symbol"], a.symbol)
        assertEquals(m, e["name"], a.name)
        assertNum("$m price", e["priceUsd"], a.priceUsd)
        assertNum("$m mcap", e["marketCap"], a.marketCap)
        assertNum("$m change", e["marketCapChange24h"], a.marketCapChange24h)
        assertNum("$m volume", e["volume24h"], a.volume24h)
        assertNum("$m fdv", e["fdv"], a.fdv)
        assertNum("$m circ", e["circulatingSupply"], a.circulatingSupply)
        assertNum("$m total", e["totalSupply"], a.totalSupply)
        assertNum("$m max", e["maxSupply"], a.maxSupply)
        assertEquals(m, num(e["lastUpdated"])?.toLong(), a.lastUpdated)
    }

    @Test
    fun pick() {
        val parsed = SpotMarket.parse(data["body"] as String)
        list("pick").forEach { c ->
            assertEquals("pick: $c", c["expected"], SpotMarket.pick(parsed, c["symbol"] as String)?.id)
        }
    }

    @Test
    fun ratios() {
        list("volumeToMarketCap").forEach { c ->
            assertNum("vol/mcap: $c", c["expected"], SpotMarket.volumeToMarketCap(num(c["volume"]), num(c["marketCap"])))
        }
        list("supplyRatio").forEach { c ->
            assertNum("supply: $c", c["expected"], SpotMarket.supplyRatio(num(c["circulating"]), num(c["max"])))
        }
    }

    @Test
    fun formatting() {
        list("compact").forEach { c ->
            assertEquals("compact: $c", c["expected"], SpotMarket.compact(num(c["value"]) ?: Double.NaN, us))
        }
        list("usd").forEach { c -> assertEquals("usd: $c", c["expected"], SpotMarket.usd(num(c["value"]), us)) }
        list("supply").forEach { c ->
            assertEquals("supply: $c", c["expected"], SpotMarket.supply(num(c["value"]), c["symbol"] as String, us))
        }
        list("percent").forEach { c -> assertEquals("percent: $c", c["expected"], SpotMarket.percent(num(c["value"]), us)) }
    }

    @Test
    fun symbolsAndEligibility() {
        list("lookupSymbol").forEach { c ->
            assertEquals("lookup: $c", c["expected"], SpotMarket.lookupSymbol(c["base"] as String))
        }
        list("url").forEach { c -> assertEquals(c["expected"], SpotMarket.url(c["symbol"] as String)) }
        list("eligible").forEach { c ->
            assertEquals("eligible: $c", c["expected"], SpotMarket.eligible(c["marketKey"] as String?, c["tradFi"] as Boolean))
        }
    }

    @Test
    fun isoMillis() {
        assertEquals(1791722096789L, SpotMarket.isoMillis("2026-10-11T12:34:56.789Z"))
        assertNull(SpotMarket.isoMillis("garbage"))
        assertNull(SpotMarket.isoMillis(null))
    }
}
