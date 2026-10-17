package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.pairListPaging
import com.cryptochecker.marketdata.model.market.Gemini
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Gemini: Symbole, die die Liste nicht trennen kann, holen Basis/Quote aus /v1/symbols/details als Folgeseiten der
 * Paarliste (keine vertauschte Schätzung; über der Grenze ungeklärt bis zur nächsten Synchronisierung; Warteschlange
 * je Synchronisierung, nicht in der geteilten Börse); dazu Hyperliquid als Mittelkurs. Gegenstück: `Tests/GeminiDetailsTests.swift`.
 */
class GeminiDetailsTest {

    private fun parse(market: Gemini, body: String): List<MarketPair> =
        mutableListOf<MarketPair>().also { market.readPairList(0, body, it) }

    @Test
    fun ambiguousSymbolsComeFromDetailsPages() {
        val market = Gemini()
        val listBody = """["btcusd","btcgusd","mogusd","abcgusd","ethusd"]"""
        val list = parse(market, listBody)
        // Mehrdeutig (gusd/usd) → immer Details, auch btcgusd trotz bekanntem BTC: nichts geraten
        assertEquals(listOf("btcusd", "ethusd"), list.map { it.pairId })
        val paging = market.pairListPaging()
        assertEquals("https://api.gemini.com/v1/symbols/details/btcgusd", paging.nextPage(0, listBody))
        // Details entscheiden: BTC/GUSD
        val btc = """{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD","status":"open"}"""
        val btcPair = parse(market, btc).single()
        assertEquals(Triple("btcgusd", "BTC", "GUSD"), Triple(btcPair.pairId, btcPair.base, btcPair.quote))
        assertEquals("https://api.gemini.com/v1/symbols/details/mogusd", paging.nextPage(0, btc))
        // Fehlerantwort: nicht raten — mogusd bleibt ungeklärt (fehlt in der Paarliste)
        val error = """{"result":"error","reason":"InvalidSymbol","message":"x"}"""
        assertEquals(emptyList<MarketPair>(), parse(market, error))
        assertEquals("https://api.gemini.com/v1/symbols/details/abcgusd", paging.nextPage(0, error))
        // Details entscheiden: ABC/GUSD (die Liste hätte ABCG/USD gesagt)
        val detail = """{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD","status":"open"}"""
        val abc = parse(market, detail).single()
        assertEquals(Triple("abcgusd", "ABC", "GUSD"), Triple(abc.pairId, abc.base, abc.quote))
        assertNull(paging.nextPage(0, detail))
    }

    @Test
    fun concurrentSyncsKeepTheirOwnQueue() {
        // Zwei Synchronisierungen derselben (geteilten) Börse greifen ineinander: jede behält ihre Warteschlange
        val market = Gemini()
        val listA = """["btcgusd","mogusd"]"""
        val listB = """["abcgusd"]"""
        val syncA = market.pairListPaging()
        val syncB = market.pairListPaging()
        assertEquals("https://api.gemini.com/v1/symbols/details/btcgusd", syncA.nextPage(0, listA))
        assertEquals("https://api.gemini.com/v1/symbols/details/abcgusd", syncB.nextPage(0, listB))
        val btc = """{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD"}"""
        assertEquals("https://api.gemini.com/v1/symbols/details/mogusd", syncA.nextPage(0, btc))
        val abc = """{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD"}"""
        assertNull(syncB.nextPage(0, abc))
        val mog = """{"symbol":"MOGUSD","base_currency":"MOG","quote_currency":"USD"}"""
        assertNull(syncA.nextPage(0, mog))
    }

    @Test
    fun marketsWithoutOwnStatePageFromTheResponse() {
        // Bybit: Cursor steht in der Antwort — der Blätterzustand leitet nur an pairListNextPage weiter
        val bybit = com.cryptochecker.marketdata.model.market.Bybit()
        val body = """{"retCode":0,"result":{"category":"spot","list":[],"nextPageCursor":"abc"}}"""
        assertEquals(
            "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000&cursor=abc",
            bybit.pairListPaging().nextPage(0, body),
        )
    }

    @Test
    fun unreadableCursorPageFailsInsteadOfStopping() {
        // Bybit: Eine unlesbare Seite wirft — still aufhören liesse die Paare der weiteren Seiten unbemerkt weg.
        // Lesbar ohne «result» oder ohne Cursor: letzte Seite. Wie `GeminiDetailsTests.swift`.
        val paging = com.cryptochecker.marketdata.model.market.Bybit().pairListPaging()
        assertThrows(Exception::class.java) { paging.nextPage(0, "<html>502 Bad Gateway</html>") }
        assertThrows(Exception::class.java) { paging.nextPage(0, "[]") }
        assertNull(paging.nextPage(0, """{"retCode":10001,"retMsg":"x"}"""))
        assertNull(paging.nextPage(0, """{"retCode":0,"result":{"nextPageCursor":""}}"""))
    }

    @Test
    fun oneKnownBaseDoesNotShortcutTheSplit() {
        // «mousd» macht MO bekannt — «mogusd» darf deshalb nicht MO/GUSD werden, sondern geht über die Details
        val plan = Gemini.planSymbols(listOf("mousd", "mogusd"))
        assertEquals(listOf("mousd"), plan.direct.map { it.first })
        assertEquals(listOf("mogusd"), plan.deferred.map { it.first })
    }

    @Test
    fun detailsCappedByPageLimit() {
        val symbols = (1..12).map { "x${'a' + it}gusd" }
        val plan = Gemini.planSymbols(symbols)
        assertEquals(Gemini.MAX_DETAILS, plan.deferred.size)
        // Über der Grenze: nie geraten — ungeklärt, nicht in der Paarliste
        assertEquals(0, plan.direct.size)
        assertEquals(12 - Gemini.MAX_DETAILS, plan.unresolved.size)
        // Perpetual in den Details: kein Spot-Paar
        val perp = org.json.JSONObject("""{"symbol":"BTCGUSDPERP","base_currency":"BTC","quote_currency":"GUSD","product_type":"swap"}""")
        assertNull(Gemini.detailPair(perp))
    }

    @Test
    fun overflowResolvedByLaterSync() {
        val market = Gemini()
        val symbols = (1..12).map { "x${'a' + it}gusd" }
        val listBody = symbols.joinToString(",", "[", "]") { "\"$it\"" }
        val detail = { s: String -> """{"symbol":"${s.uppercase()}","base_currency":"${s.dropLast(4).uppercase()}","quote_currency":"GUSD"}""" }

        // 1. Synchronisierung: 9 Details, die übrigen 3 fehlen (ungeklärt statt geraten)
        val first = parse(market, listBody).toMutableList()
        var paging = market.pairListPaging()
        var url = paging.nextPage(0, listBody)
        var asked = 0
        while (url != null) {
            val body = detail(url.substringAfterLast('/'))
            first += parse(market, body)
            url = paging.nextPage(0, body)
            asked++
        }
        assertEquals(Gemini.MAX_DETAILS, asked)
        assertEquals(Gemini.MAX_DETAILS, first.size)
        org.junit.Assert.assertTrue(first.all { it.quote == "GUSD" })

        // 2. Synchronisierung: die 9 aus dem Zwischenspeicher, die 3 übrigen über Details — danach vollständig
        val second = parse(market, listBody).toMutableList()
        assertEquals(Gemini.MAX_DETAILS, second.size)
        paging = market.pairListPaging()
        url = paging.nextPage(0, listBody)
        asked = 0
        while (url != null) {
            val body = detail(url.substringAfterLast('/'))
            second += parse(market, body)
            url = paging.nextPage(0, body)
            asked++
        }
        assertEquals(12 - Gemini.MAX_DETAILS, asked)
        assertEquals(symbols.toSet(), second.map { it.pairId }.toSet())
        org.junit.Assert.assertTrue(second.all { it.quote == "GUSD" && it.base.length == 2 })
    }

    @Test
    fun detailsCachedBySymbolNotCursor() {
        // Antworten versetzt (zuerst ABCGUSD, obwohl btcgusd gefragt war): Ablage unter dem Symbol der Antwort
        val market = Gemini()
        val listBody = """["btcgusd","abcgusd"]"""
        parse(market, listBody)
        val paging = market.pairListPaging()
        paging.nextPage(0, listBody)
        val abc = """{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD"}"""
        parse(market, abc)
        paging.nextPage(0, abc)
        val btc = """{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD"}"""
        parse(market, btc)
        // Nächste Synchronisierung aus dem Zwischenspeicher: jedes Symbol mit seinen eigenen Details
        val cached = parse(market, listBody).associate { it.pairId to it.base }
        assertEquals(mapOf("btcgusd" to "BTC", "abcgusd" to "ABC"), cached)
    }

    @Test
    fun hyperliquidReportsMidPriceWithoutExchangeTime() {
        // Mittelkurs statt letztem Handel: das Aktionsblatt nennt «Mittelkurs»; Zeit = Abruf (Fixture: exchangeTime null)
        org.junit.Assert.assertTrue(com.cryptochecker.marketdata.model.market.Hyperliquid().priceIsMid)
        org.junit.Assert.assertFalse(Gemini().priceIsMid)
    }
}
