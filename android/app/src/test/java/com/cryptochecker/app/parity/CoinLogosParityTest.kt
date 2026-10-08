package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `coin_logos.json` (iOS: `CoinLogosTests.swift`) plus Zwischenspeicher. */
class CoinLogosParityTest {

    private val data = ParityJson.load("coin_logos.json")

    @Test
    fun normalize() {
        data["normalize"].list().map { it.obj() }.forEach { c ->
            assertEquals("normalize: $c", c["expected"], CoinLogos.normalize(c["symbol"] as String))
        }
    }

    @Test
    fun initials() {
        data["initials"].list().map { it.obj() }.forEach { c ->
            assertEquals("initials: $c", c["expected"], CoinLogos.initials(c["symbol"] as String))
        }
    }

    @Test
    fun allowedUrl() {
        data["allowedUrl"].list().map { it.obj() }.forEach { c ->
            assertEquals("allowed: $c", c["expected"], CoinLogos.isAllowedUrl(c["url"] as String?))
        }
    }

    @Test
    fun smallUrl() {
        data["smallUrl"].list().map { it.obj() }.forEach { c ->
            assertEquals("small: $c", c["expected"], CoinLogos.smallUrl(c["url"] as String))
        }
    }

    @Test
    fun market() {
        data["market"].list().map { it.obj() }.forEach { c ->
            assertEquals("market: $c", c["expected"], CoinLogos.allowedFor(c["marketKey"] as String?))
        }
    }

    @Test
    fun fileName() {
        data["fileName"].list().map { it.obj() }.forEach { c ->
            assertEquals("fileName: $c", c["expected"], CoinLogos.fileName(c["symbol"] as String))
        }
    }

    @Test
    fun pickFirstOccurrenceWins() {
        val pick = data["pick"].obj()
        val ranked = pick["ranked"].list().map { it.obj() }.map { (it["symbol"] as String) to (it["image"] as String?) }
        val expected = pick["expected"].obj().mapValues { it.value as String }
        val actual = CoinLogos.pick(ranked)
        assertEquals(expected, actual)
        // Reihenfolge = Rangliste
        assertEquals(expected.keys.toList(), actual.keys.toList())
    }

    @Test
    fun binanceFillsOnlyGaps() {
        val pick = data["pick"].obj()
        val ranked = pick["ranked"].list().map { it.obj() }.map { (it["symbol"] as String) to (it["image"] as String?) }
        val map = LinkedHashMap<String, String>()
        CoinLogos.pick(ranked, map)
        val fill = data["fill"].obj()
        val binance = fill["binance"].list().map { it.obj() }.map { (it["symbol"] as String) to (it["image"] as String?) }
        CoinLogos.pick(binance, map)
        assertEquals(fill["expected"].obj().mapValues { it.value as String }, map)
    }

    @Test
    fun encodeDecodeRoundTripDropsInvalidLines() {
        val map = linkedMapOf(
            "BTC" to "https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png",
            "ETH" to "https://coin-images.coingecko.com/coins/images/279/large/ethereum.png",
        )
        assertEquals(map, CoinLogos.decode(CoinLogos.encode(map)))
        val broken = CoinLogos.encode(map) + "\nXRP\thttp://insecure.example/x.png\n\tno-symbol\nDOGE"
        assertEquals(map, CoinLogos.decode(broken))
        assertTrue(CoinLogos.decode(null).isEmpty())
        assertTrue(CoinLogos.decode("  ").isEmpty())
    }

    @Test
    fun freshness() {
        val now = 10L * CoinLogos.MAP_TTL_MILLIS
        assertTrue(CoinLogos.isFresh(now - 1_000, now))
        assertFalse(CoinLogos.isFresh(now - CoinLogos.MAP_TTL_MILLIS, now))
        assertFalse(CoinLogos.isFresh(0, now))
        // Uhr zurückgestellt: nicht frisch, neu holen
        assertFalse(CoinLogos.isFresh(now + 1_000, now))
    }
}
