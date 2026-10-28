package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Gemeinsame Testfälle für Adressen und Abrufe der Logo-Dateien (`coin_logos.json`: urlFor, downloadJobs, futures) und
 * das Nachholen nach Pausen der Bild-Server (`logo_fallback.json`: pauseRetry) — iOS:
 * `LogoFilesTests.swift`.
 */
class LogoFilesParityTest {

    private val logos = ParityJson.load("coin_logos.json")
    private val fallback = ParityJson.load("logo_fallback.json")

    private fun Any?.strings(): Map<String, String> = obj().mapValues { it.value as String }

    @Test
    fun urlForUsesTheMergeKey() {
        val u = logos["urlFor"].obj()
        val map = u["map"].strings()
        u["cases"].list().map { it.list() }.forEach { (symbol, expected) ->
            assertEquals("urlFor: $symbol", expected, CoinLogos.urlFor(map, symbol as String))
        }
    }

    @Test
    fun downloadJobsOnePerFile() {
        val d = logos["downloadJobs"].obj()
        val wanted = d["wanted"].list().map { it.list() }.map { (s, u) -> (s as String) to (u as String) }
        val skip = d["skip"].list().map { it as String }.toSet()
        val expected = d["expected"].list().map { it.list() }.map { (n, u) -> (n as String) to (u as String) }
        assertEquals(expected, CoinLogos.downloadJobs(wanted) { it in skip })
    }

    /** Futures-Kürzel der Merkliste über die echte Zusammenführung: Adresse und Datei je Kürzel. */
    @Test
    fun futuresSymbolsFindTheirLogo() {
        val f = logos["futures"].obj()
        val markets = CoinMeta.parseMarketsPage(f["markets"] as String)
        val meta = CoinMeta.parse(f["meta"] as String)
        assertNotNull(markets)
        assertNotNull(meta)
        if (markets == null || meta == null) return
        val map = CoinMeta.merge(markets, meta.binance, meta.alpha).map
        f["cases"].list().map { it.list() }.forEach { (symbol, url, file) ->
            assertEquals("url: $symbol", url, CoinLogos.urlFor(map, symbol as String))
            assertEquals("file: $symbol", file, CoinLogos.fileName(symbol))
        }
    }

    @Test
    fun pauseRetryDelay() {
        fallback["pauseRetry"].list().map { it.list() }.forEach { (until, now, expected) ->
            assertEquals(
                "pauseRetry: $until/$now",
                (expected as Double?)?.toLong(),
                CoinLogoRetry.pauseRetryDelayMillis((until as Double?)?.toLong(), (now as Double).toLong()),
            )
        }
    }
}
