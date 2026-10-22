package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.cryptochecker.app.domain.logos.alphaIconUrl
import com.cryptochecker.app.domain.logos.alphaSymbols
import com.cryptochecker.app.domain.logos.applyAlpha
import com.cryptochecker.app.domain.logos.bundledIconName
import com.cryptochecker.app.domain.logos.cleanSymbol
import com.cryptochecker.app.domain.logos.parseAlpha
import com.cryptochecker.app.domain.logos.parseIconPack

/**
 * Gemeinsame Testfälle `alpha_list.json` (Antwort der Binance-Alpha-Liste mit FROGGIE) und
 * `logo_fallback.json` (mitgeliefertes Icon-Paket, Pausen der Bild-Server) — iOS: `LogoFallbackTests.swift`.
 */
class LogoFallbackParityTest {

    private val alpha = ParityJson.load("alpha_list.json")
    private val fallback = ParityJson.load("logo_fallback.json")

    /** Rohtext der Antwort (wie vom Server), damit der echte Leser ([CoinLogos.parseAlpha]) läuft. */
    private fun alphaResponseText(): String =
        org.json.JSONObject(File(ParityJson.dir(), "alpha_list.json").readText(Charsets.UTF_8))
            .getJSONObject("response").toString()

    @Test
    fun alphaList_parsesFroggie() {
        val entries = CoinLogos.parseAlpha(alphaResponseText())
        assertNotNull(entries)
        if (entries == null) return
        assertEquals(alpha["expectedOrder"], entries.map { it.symbol })
        val symbols = CoinLogos.alphaSymbols(entries)
        assertTrue("FROGGIE fehlt: $symbols", "FROGGIE" in symbols)
        assertEquals(alpha["expectedSymbols"].list().toSet(), symbols)
        assertEquals(alpha["expectedIcons"], CoinLogos.pick(entries.map { it.symbol to it.icon }))
        // Wie in der App: applyAlpha liefert dieselben Kürzel
        val map = LinkedHashMap<String, String>()
        assertEquals(symbols, CoinLogos.applyAlpha(entries, map, LinkedHashMap()))
        assertEquals("https://bin.bnbstatic.com/images/web3-data/public/token/logos/froggie.png", map["FROGGIE"])
    }

    @Test
    fun alphaList_invalidResponses() {
        alpha["invalid"].list().forEach { text -> assertNull("invalid: $text", CoinLogos.parseAlpha(text as String)) }
        assertNull(CoinLogos.parseAlpha(null))
    }

    @Test
    fun alphaList_cleanSymbolAndIcon() {
        alpha["cleanSymbol"].list().map { it.list() }.forEach { (raw, expected) ->
            assertEquals("clean: $raw", expected, CoinLogos.cleanSymbol(raw as String))
        }
        alpha["iconUrl"].list().map { it.list() }.forEach { (raw, expected) ->
            assertEquals("icon: $raw", expected, CoinLogos.alphaIconUrl(raw as String))
        }
    }

    @Test
    fun iconPack_index() {
        val pack = fallback["iconPack"].obj()
        val index = CoinLogos.parseIconPack((pack["text"] as String).toByteArray(Charsets.US_ASCII))
        assertNotNull(index)
        val expected = pack["expected"].obj().mapValues { (_, v) ->
            val (offset, length) = v.list().map { (it as Double).toInt() }
            CoinLogos.IconSlice(offset, length)
        }
        assertEquals(expected, index)
        assertEquals(emptyMap<String, CoinLogos.IconSlice>(), CoinLogos.parseIconPack((pack["emptyText"] as String).toByteArray()))
        fallback["invalidPack"].list().forEach { text ->
            assertNull("invalid: $text", CoinLogos.parseIconPack((text as String).toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun iconPack_names() {
        fallback["bundledName"].list().map { it.list() }.forEach { (symbol, expected) ->
            assertEquals("name: $symbol", expected, CoinLogos.bundledIconName(symbol as String))
        }
    }

    /** Das mitgelieferte Paket selbst: lesbar, alle Einträge PNG, BTC und ETH dabei. */
    @Test
    fun iconPack_bundledAsset() {
        val file = File(ParityJson.dir(), "../../app/src/main/assets/" + CoinLogos.ICON_PACK_FILE)
        assertTrue("assets/${CoinLogos.ICON_PACK_FILE} nicht gefunden (seit Build 105 Pflicht)", file.isFile)
        val bytes = file.readBytes()
        val index = CoinLogos.parseIconPack(bytes)
        assertNotNull(index)
        if (index == null) return
        assertTrue("zu wenige Icons: ${index.size}", index.size > 400)
        assertTrue("btc" in index && "eth" in index)
        index.values.forEach { slice ->
            assertEquals(0x89.toByte(), bytes[slice.offset])
            assertEquals('P'.code.toByte(), bytes[slice.offset + 1])
        }
    }

    @Test
    fun imagePauses() {
        fallback["pauseCodes"].list().map { it.list() }.forEach { (code, expected) ->
            assertEquals("code: $code", expected, CoinLogoRetry.pausesImages((code as Double).toInt()))
        }
        fallback["pauseMillis"].list().map { it.list() }.forEach { (retry, expected) ->
            assertEquals("pause: $retry", (expected as Double).toLong(), CoinLogoRetry.imagePauseMillis((retry as Double?)?.toLong()))
        }
        fallback["host"].list().map { it.list() }.forEach { (url, expected) ->
            assertEquals("host: $url", expected, CoinLogoRetry.hostOf(url as String))
        }
        val p = fallback["pauses"].obj()
        val decoded = CoinLogoRetry.decodePauses(p["text"] as String, (p["now"] as Double).toLong())
        assertEquals(p["expected"].obj().mapValues { (it.value as Double).toLong() }, decoded)
        assertEquals(p["encoded"], CoinLogoRetry.encodePauses(decoded))
    }
}
