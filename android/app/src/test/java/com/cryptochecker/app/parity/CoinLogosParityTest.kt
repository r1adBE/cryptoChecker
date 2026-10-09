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
    fun tradFiNeverFromCoinGecko() {
        val t = data["tradFi"].obj()
        val crypto = t["crypto"].list().map { it as String }.toSet()
        val entries = t["binance"].list().map { it.obj() }.map {
            CoinLogos.BinanceEntry(
                name = it["name"] as String,
                logo = it["logo"] as String?,
                tags = it["tags"].list().map { tag -> tag as String },
                onlyFutures = it["onlyFutures"] as Boolean,
                fullName = it["fullName"] as String?,
            )
        }
        assertEquals(t["expectedNames"].obj().mapValues { it.value as String }, CoinLogos.pickTradFiNames(entries, crypto))
        val actual = CoinLogos.pickTradFi(entries, crypto)
        assertEquals(t["expected"].obj().mapValues { it.value as String }, actual)
        assertEquals(t["order"].list().map { it as String }, actual.keys.toList())
        // Schlüssel und Namensraum
        assertEquals("TRADFI:NVDA", CoinLogos.logoKey("nvda", tradFi = true))
        assertEquals("nvda", CoinLogos.logoKey("nvda", tradFi = false))
        assertEquals("BinanceFutures|NVDA|USDT|PERPETUAL", CoinLogos.pairKey("BinanceFutures", "nvda", "usdt", "PERPETUAL"))
    }

    @Test
    fun stockLogos() {
        data["stockLogo"].list().map { it.obj() }.forEach { c ->
            assertEquals("stockLogo: $c", c["expected"], CoinLogos.stockLogoUrl(c["symbol"] as String))
        }
        val w = data["withStockLogos"].obj()
        val map = w["map"].obj().mapValues { it.value as String }
        val bases = w["bases"].list().map { it as String }
        assertEquals(w["expected"].obj().mapValues { it.value as String }, CoinLogos.withStockLogos(map, bases))
    }

    @Test
    fun stockNames() {
        data["cleanStockName"].list().map { it.obj() }.forEach { c ->
            assertEquals("cleanStockName: $c", c["expected"], CoinLogos.cleanStockName(c["raw"] as String?))
        }
        val d = data["symbolDirectory"].obj()
        val parsed = LinkedHashMap<String, String>()
        CoinLogos.parseSymbolDirectory(d["other"] as String, parsed)
        CoinLogos.parseSymbolDirectory(d["nasdaq"] as String, parsed)
        assertEquals(d["expectedOtherFirst"].obj().mapValues { it.value as String }, parsed)
        val w = data["withStockNames"].obj()
        assertEquals(
            w["expected"].obj().mapValues { it.value as String },
            CoinLogos.withStockNames(
                w["names"].obj().mapValues { it.value as String },
                w["stockNames"].obj().mapValues { it.value as String },
                w["bases"].list().map { it as String },
            )
        )
    }

    @Test
    fun names() {
        data["cleanName"].list().map { it.obj() }.forEach { c ->
            assertEquals("cleanName: $c", c["expected"], CoinLogos.cleanName(c["raw"] as String?, c["symbol"] as String?))
        }
        val n = data["names"].obj()
        val ranked = n["ranked"].list().map { it.obj() }.map { (it["symbol"] as String) to (it["name"] as String?) }
        assertEquals(n["expected"].obj().mapValues { it.value as String }, CoinLogos.pickNames(ranked))
        val names = linkedMapOf("BTC" to "Bitcoin", "TRADFI:NVDA" to "NVIDIA")
        assertEquals(names, CoinLogos.decodeNames(CoinLogos.encodeNames(names) + "\nBROKEN\n\tNoKey"))
        assertEquals("TRADFI:NVDA", CoinLogos.nameKey("nvda", tradFi = true))
        assertEquals("PEPE", CoinLogos.nameKey("1000PEPE", tradFi = false))
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
        // TradFi-Zeilen bleiben erhalten
        val withTradFi = map + ("TRADFI:NVDA" to "https://bin.bnbstatic.com/image/nvdab.png")
        assertEquals(withTradFi, CoinLogos.decode(CoinLogos.encode(withTradFi)))
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
    @Test
    fun alphaList() {
        val a = data["alpha"].obj()
        val entries = a["entries"].list().map { it.obj() }.map {
            CoinLogos.AlphaEntry(
                symbol = it["symbol"] as String,
                name = it["name"] as String?,
                icon = it["iconUrl"] as String?,
                marketCap = (it["marketCap"] as String?)?.toDoubleOrNull(),
                offline = it["offline"] as Boolean,
            )
        }
        val ranked = CoinLogos.rankAlpha(entries)
        assertEquals(a["expectedOrder"].list().map { it as String }, ranked.map { it.symbol })
        val map = LinkedHashMap(a["known"].obj().mapValues { it.value as String })
        CoinLogos.pick(ranked.map { it.symbol to it.icon }, map)
        assertEquals(a["expected"].obj().mapValues { it.value as String }, map)
        assertEquals(a["expectedNames"].obj().mapValues { it.value as String }, CoinLogos.pickNames(ranked.map { it.symbol to it.name }))
    }

    @Test
    fun partialList() {
        val p = data["partial"].obj()
        val merged = CoinLogos.withKnown(p["fresh"].obj().mapValues { it.value as String }, p["known"].obj().mapValues { it.value as String })
        assertEquals(p["expected"].obj().mapValues { it.value as String }, merged)
        p["rateLimit"].list().map { it.obj() }.forEach { c ->
            assertEquals("rateLimit: $c", (c["expected"] as Number).toLong(), CoinLogos.rateLimitWaitMillis((c["retryAfter"] as Number?)?.toLong()))
        }
        val now = 10L * CoinLogos.MAP_TTL_MILLIS
        assertEquals(now, CoinLogos.savedAtFor(now, complete = true))
        val partial = CoinLogos.savedAtFor(now, complete = false)
        assertTrue(CoinLogos.isFresh(partial, now + CoinLogos.PARTIAL_TTL_MILLIS - 1))
        assertFalse(CoinLogos.isFresh(partial, now + CoinLogos.PARTIAL_TTL_MILLIS))
    }
    @Test
    fun githubIndex() {
        val x = data["index"].obj()
        val index = CoinLogos.parseIndex(x["text"] as String)!!
        assertEquals(x["expectedLogos"].obj().mapValues { it.value as String }, index.logos)
        assertEquals(x["expectedNames"].obj().mapValues { it.value as String }, index.names)
        x["invalid"].list().forEach { assertEquals("invalid: $it", null, CoinLogos.parseIndex(it as String)) }
        assertEquals(null, CoinLogos.parseIndex(null))
        assertTrue(CoinLogos.isFromIndex(index.logos))
        assertFalse(CoinLogos.isFromIndex(mapOf("BTC" to "https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png")))
        val c = x["changed"].obj()
        assertEquals(
            c["expected"].list().map { it as String }.toSet(),
            CoinLogos.changedKeys(c["old"].obj().mapValues { it.value as String }, c["new"].obj().mapValues { it.value as String }),
        )
    }
    @Test
    fun pack() {
        val x = data["pack"].obj()
        val b64 = java.util.Base64.getDecoder()
        val pack = CoinLogos.parsePack(b64.decode(x["base64"] as String))!!
        val expected = x["expected"].obj().mapValues { b64.decode(it.value as String).toList() }
        assertEquals(expected, pack.mapValues { it.value.toList() })
        x["invalidBase64"].list().forEach { assertEquals(null, CoinLogos.parsePack(b64.decode(it as String))) }
    }
}
