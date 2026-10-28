package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.app.domain.watch.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import com.cryptochecker.app.domain.logos.parseAlpha

/**
 * Gemeinsame Testfälle `coin_meta.json` (iOS: `CoinMetaTests.swift`): ein ganzer Abgleich mit
 * Antworten wie vom Server (CoinGecko-Seite, Binance-Symbolliste, Alpha-Liste) und der Ersatz aus
 * `coin_meta.json` — geprüft wird, was die Oberfläche danach liest (Name über [CoinLogos.nameKey],
 * Logo-Adresse über [CoinLogos.normalize], Alpha über [AssetKind.of]).
 */
class CoinMetaParityTest {

    private val data = ParityJson.load("coin_meta.json")

    /** Teil der Datei als Rohtext (wie vom Server), damit die echten Leser laufen. */
    private fun raw(key: String): String {
        val root = org.json.JSONObject(File(ParityJson.dir(), "coin_meta.json").readText(Charsets.UTF_8))
        return root.get(key).toString()
    }

    private fun assertUiReads(lists: CoinMeta.Lists, expected: Map<String, Any?>) {
        expected["names"].obj().forEach { (key, name) ->
            val tradFi = key.startsWith(CoinLogos.TRADFI_PREFIX)
            val symbol = CoinLogos.displaySymbol(key)
            assertEquals("Name $key", name, lists.names[CoinLogos.nameKey(symbol, tradFi)])
        }
        expected["map"].obj().forEach { (key, url) ->
            assertEquals("Logo $key", url, lists.map[key])
            assertNotNull("Dateiname $key", CoinLogos.fileName(key))
        }
        assertEquals(expected["alpha"].list().toSet(), lists.alpha)
    }

    @Test
    fun fullSync_namesLogosAlphaReachUi() {
        val gecko = CoinMeta.parseMarketsPage(raw("geckoPage"))
        val binance = CoinMeta.parseBinanceList(raw("binanceList"))
        val alpha = CoinLogos.parseAlpha(raw("alphaList"))
        assertNotNull(gecko)
        assertNotNull(binance)
        assertNotNull(alpha)
        if (gecko == null || binance == null || alpha == null) return
        val lists = CoinMeta.merge(gecko, binance, alpha)
        val expected = data["fullSync"].obj()
        assertUiReads(lists, expected)
        val alphaSymbols = lists.alpha.orEmpty()
        expected["kinds"].list().map { it.obj() }.forEach { c ->
            val kind = AssetKind.of(c["exchangeKey"] as String, c["base"] as String, c["tradFi"] as Boolean, alphaSymbols)
            assertEquals("kind $c", c["expected"], kind.name)
        }
        // Binance-Namen für «gleicher Token»: Spot vor Futures, Kürzel gross
        assertEquals("Worldcoin", CoinMeta.binanceFullNames(binance)["WLD"])
    }

    @Test
    fun metaOnly_whenDirectListsFail() {
        val meta = CoinMeta.parse(raw("coinMeta"))
        assertNotNull(meta)
        if (meta == null) return
        val expected = data["metaOnly"].obj()
        assertEquals((expected["generated"] as Double).toLong(), meta.generatedAt)
        // CoinGecko und beide Binance-Listen fehlen: nur die Ersatzdatei
        assertUiReads(CoinMeta.merge(emptyList(), meta.binance, meta.alpha), expected)
    }

    @Test
    fun invalidMeta() {
        data["invalidMeta"].list().forEach { text -> assertNull("invalid: $text", CoinMeta.parse(text as String?)) }
        assertNull(CoinMeta.parseBinanceList("{\"data\":1}"))
        assertNull(CoinMeta.parseMarketsPage("{}"))
    }

    @Test
    fun usableAndNeeded() {
        val meta = CoinMeta.Meta(0, emptyList(), emptyList())
        data["usable"].list().map { it.obj() }.forEach { c ->
            val m = meta.copy(generatedAt = (c["generated"] as Double).toLong())
            assertEquals("usable $c", c["expected"], CoinMeta.isUsable(m, (c["now"] as Double).toLong()))
        }
        data["needed"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                "needed $c", c["expected"],
                CoinMeta.needed(c["directOk"] as Boolean, (c["lastDirectAt"] as Double).toLong(), (c["now"] as Double).toLong()),
            )
        }
    }
}
