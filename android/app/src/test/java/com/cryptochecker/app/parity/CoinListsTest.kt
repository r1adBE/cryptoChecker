package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.app.domain.watch.AssetKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Ende-zu-Ende mit Antworten wie vom Server (`coin_meta.json`; iOS: `CoinListsTests.swift`): Namen und
 * Alpha-Kennzeichen stehen nach den beiden Binance-Listen bereit — ohne dass CoinGecko oder ein
 * Bild-Server gefragt wurde. CoinGecko- und Bild-Abrufe des Testabrufers hängen absichtlich für immer:
 * Wartete der erste Schritt auf sie, liefe der Test in die Zeitgrenze.
 */
class CoinListsTest {

    private val data = ParityJson.load("coin_meta.json")

    private fun raw(key: String): String {
        val root = org.json.JSONObject(File(ParityJson.dir(), "coin_meta.json").readText(Charsets.UTF_8))
        return root.get(key).toString()
    }

    private val generated = 1_760_400_000_000L

    /** Abrufer mit festen Antworten; CoinGecko und Bilder hängen, alle Adressen werden notiert. */
    private class FakeServer(val responses: Map<String, () -> String>) {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val never = CompletableDeferred<String>()

        suspend fun fetch(url: String): String {
            calls += url
            responses[url]?.let { return it() }
            if ("coingecko" in url || url.endsWith(".png")) return never.await()
            throw IOException("unbekannt: $url")
        }
    }

    private fun request(binance: Boolean = true, alpha: Boolean = true, lastBinance: Long = 0, lastAlpha: Long = 0) =
        CoinLists.Request(binance, alpha, lastBinance, lastAlpha, now = generated + 60_000)

    private fun run(server: FakeServer, request: CoinLists.Request): CoinLists.Result = runBlocking {
        withTimeout(5_000) { CoinLists.fetch(server::fetch, request) { it is IOException } }
    }

    @Test
    fun namesAndAlpha_beforeCoinGeckoAndImages() {
        val server = FakeServer(mapOf(
            CoinLogos.BINANCE_LIST_URL to { raw("binanceList") },
            CoinLogos.ALPHA_LIST_URL to { raw("alphaList") },
        ))
        val result = run(server, request())
        assertEquals(CoinLists.Source.DIRECT, result.binanceSource)
        assertEquals(CoinLists.Source.DIRECT, result.alphaSource)
        assertFalse(result.networkError)
        // Nur die beiden Listen — weder CoinGecko noch Bilder noch die Ersatzdatei
        assertEquals(setOf(CoinLogos.BINANCE_LIST_URL, CoinLogos.ALPHA_LIST_URL), server.calls.toSet())

        // Was die App sofort veröffentlicht (CoinLogoRepository.refreshLists)
        val binance = result.binance
        val alpha = result.alpha
        assertNotNull(binance)
        assertNotNull(alpha)
        if (binance == null || alpha == null) return
        val names = CoinLists.publishNames(emptyMap(), false, CoinMeta.listNames(binance, alpha))
        val expected = data["listsOnly"].obj()
        expected["names"].obj().forEach { (key, name) ->
            val tradFi = key.startsWith(CoinLogos.TRADFI_PREFIX)
            assertEquals("Name $key", name, names[CoinLogos.nameKey(CoinLogos.displaySymbol(key), tradFi)])
        }
        expected["absent"].list().forEach { key -> assertNull("kein Name für $key", names[key as String]) }
        val alphaSymbols = CoinLogos.alphaSymbols(alpha)
        assertEquals(expected["alpha"].list().toSet(), alphaSymbols)
        assertEquals(AssetKind.ALPHA, AssetKind.of("BinanceFutures", "FROGGIE", false, alphaSymbols))
        // Binance-Namen für «gleicher Token»
        assertEquals("Worldcoin", CoinMeta.binanceFullNames(binance)["WLD"])
    }

    @Test
    fun oneSourceFails_otherStillArrives() {
        val server = FakeServer(mapOf(
            CoinLogos.BINANCE_LIST_URL to { throw IllegalStateException("HTTP 500") },
            CoinLogos.ALPHA_LIST_URL to { raw("alphaList") },
            CoinMeta.URL to { throw IllegalStateException("HTTP 404") },
        ))
        val result = run(server, request())
        assertNull(result.binance)
        assertEquals(CoinLists.Source.NONE, result.binanceSource)
        assertEquals(CoinLists.Source.DIRECT, result.alphaSource)
        assertTrue(result.mirrorFailed)
        assertFalse(result.networkError)
        assertTrue(result.missing(request()))
        assertEquals(setOf("AIA", "FROGGIE"), CoinLogos.alphaSymbols(result.alpha.orEmpty()))
    }

    @Test
    fun directListsFail_mirrorFills() {
        val server = FakeServer(mapOf(
            CoinLogos.BINANCE_LIST_URL to { throw IOException("offline") },
            CoinLogos.ALPHA_LIST_URL to { throw IllegalStateException("HTTP 451") },
            CoinMeta.URL to { raw("coinMeta") },
        ))
        val result = run(server, request())
        assertEquals(CoinLists.Source.MIRROR, result.binanceSource)
        assertEquals(CoinLists.Source.MIRROR, result.alphaSource)
        assertTrue(result.networkError)
        assertFalse(result.missing(request()))
        val names = CoinMeta.listNames(result.binance, result.alpha)
        val expected = data["metaOnly"].obj()
        expected["names"].obj().forEach { (key, name) ->
            val tradFi = key.startsWith(CoinLogos.TRADFI_PREFIX)
            assertEquals("Name $key", name, names[CoinLogos.nameKey(CoinLogos.displaySymbol(key), tradFi)])
        }
        assertEquals(expected["alpha"].list().toSet(), CoinLogos.alphaSymbols(result.alpha.orEmpty()))
        assertFalse(server.calls.any { "coingecko" in it })
    }

    /**
     * Ersatzdatei nur mit Kürzeln (ohne Namen, leere Alpha-Liste) nach 48 h ohne direkte Liste: Die
     * gespeicherte direkte Liste behält Namen und Logos, nur fehlende Kürzel kommen hinzu.
     */
    @Test
    fun mirrorWithoutNames_keepsSavedNames() {
        val saved = CoinMeta.parseBinanceList(raw("binanceList")).orEmpty()
        val mirrorText = org.json.JSONObject(raw("mirrorSymbolsOnly")).getJSONObject("coinMeta").toString()
        val server = FakeServer(mapOf(
            CoinLogos.BINANCE_LIST_URL to { throw IOException("offline") },
            CoinLogos.ALPHA_LIST_URL to { throw IllegalStateException("HTTP 500") },
            CoinMeta.URL to { mirrorText },
        ))
        val request = request(lastBinance = generated - CoinMeta.DIRECT_STALE_MILLIS - 1).copy(savedBinance = saved)
        val result = run(server, request)
        assertEquals(CoinLists.Source.MIRROR, result.binanceSource)
        // Leere Alpha-Liste der Ersatzdatei: bisherige bleibt
        assertNull(result.alpha)
        assertEquals(CoinLists.Source.NONE, result.alphaSource)
        val binance = result.binance.orEmpty()
        val expected = data["mirrorSymbolsOnly"].obj()["afterDirect"].obj()
        assertEquals(expected["names"].list(), binance.map { it.name })
        val fullNames = CoinMeta.binanceFullNames(binance)
        expected["fullNames"].obj().forEach { (symbol, name) -> assertEquals("Name $symbol", name, fullNames[symbol]) }
        expected["logos"].obj().forEach { (symbol, logo) ->
            assertEquals("Logo $symbol", logo, binance.first { it.name == symbol }.logo)
        }
        expected["withoutNames"].list().forEach { symbol -> assertNull("$symbol", fullNames[symbol as String]) }
        // Ohne gespeicherte Liste füllt die Ersatzdatei wie bisher
        val fresh = run(server, request(lastBinance = 0))
        assertEquals(listOf("BTC", "ETH", "WLD", "SOL"), fresh.binance.orEmpty().map { it.name })
    }

    /** Regel [CoinMeta.mirrorBinance] (gemeinsame Fälle `mirrorSymbolsOnly.merge`). */
    @Test
    fun mirrorBinance_cases() {
        val cases = org.json.JSONObject(raw("mirrorSymbolsOnly")).getJSONArray("merge")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val mirror = CoinMeta.binanceEntries(c.getJSONArray("mirror"))
            val saved = CoinMeta.binanceEntries(c.getJSONArray("saved"))
            val expected = CoinMeta.binanceEntries(c.getJSONArray("expected"))
            assertEquals(c.getString("case"), expected, CoinMeta.mirrorBinance(mirror, saved))
        }
    }

    @Test
    fun mirrorOlderThanDirect_isIgnored() {
        val server = FakeServer(mapOf(
            CoinLogos.BINANCE_LIST_URL to { raw("binanceList") },
            CoinLogos.ALPHA_LIST_URL to { throw IllegalStateException("HTTP 500") },
            CoinMeta.URL to { raw("coinMeta") },
        ))
        val result = run(server, request(lastAlpha = generated + 1))
        assertEquals(CoinLists.Source.DIRECT, result.binanceSource)
        assertNull(result.alpha)
        assertEquals(CoinLists.Source.NONE, result.alphaSource)
    }

    @Test
    fun alphaOnly_andNothingDue() {
        val server = FakeServer(mapOf(CoinLogos.ALPHA_LIST_URL to { raw("alphaList") }))
        val result = run(server, request(binance = false))
        assertEquals(listOf(CoinLogos.ALPHA_LIST_URL), server.calls.toList())
        assertNull(result.binance)
        assertFalse(result.missing(request(binance = false)))
        val idle = FakeServer(emptyMap())
        run(idle, request(binance = false, alpha = false))
        assertTrue(idle.calls.isEmpty())
    }

    @Test
    fun offline_everythingMissing() {
        val server = FakeServer(emptyMap())
        val result = run(server, request())
        assertTrue(result.networkError)
        assertTrue(result.mirrorFailed)
        assertTrue(result.missing(request()))
    }

    @Test
    fun publishNames_precedence() {
        data["publishNames"].list().map { it.obj() }.forEach { c ->
            val known = c["known"].obj().mapValues { it.value as String }
            val lists = c["lists"].obj().mapValues { it.value as String }
            assertEquals("publish $c", c["expected"], CoinLists.publishNames(known, c["snapshot"] as Boolean, lists))
        }
    }

    /** Gespeicherte Listen (gleiches Format wie coin_meta.json) lesen sich wieder gleich. */
    @Test
    fun encodeRoundTrip() {
        val binance = CoinMeta.parseBinanceList(raw("binanceList")).orEmpty()
        val alpha = CoinLogos.parseAlpha(raw("alphaList")).orEmpty()
        val meta = CoinMeta.Meta(generated, binance, alpha)
        assertEquals(meta, CoinMeta.parse(CoinMeta.encode(meta)))
    }

    /** Der mitgelieferte Auszug ist lesbar und liefert Namen. */
    @Test
    fun bundledSnapshot() {
        val file = File(ParityJson.dir(), "../../app/src/main/assets/" + CoinMeta.SNAPSHOT_FILE)
        if (!file.isFile) return
        val meta = CoinMeta.parse(file.readText(Charsets.UTF_8))
        assertNotNull(meta)
        if (meta == null) return
        val names = CoinMeta.listNames(meta.binance, meta.alpha)
        assertTrue("zu wenige Namen: ${names.size}", names.size > 100)
        assertNotNull(names["BTC"])
    }
}
