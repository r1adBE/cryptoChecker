package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.market.Lighter
import com.cryptochecker.marketdata.util.TradFi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lighter-Paarliste aus zwei gemeinsam gelesenen Antworten (`orderBooks` + `tokenlist`) und die Namen daraus.
 * Kombinierte Paarlisten sind keine Fallart der Börsen-Fixtures (wie LATOKEN), darum hier. Zeilen gekürzt nach
 * echten Antworten vom 19.10.2026. Wie `LighterPairListTests.swift`.
 */
class LighterPairListTest {

    private val orderBooks = """{"code":200,"order_books":[
        {"symbol":"BTC","market_id":1,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"COIN","market_id":110,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"ETH/USDC","market_id":2048,"market_type":"spot","status":"active","is_frozen":false},
        {"symbol":"NEWX","market_id":300,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"FOGO","market_id":124,"market_type":"perp","status":"inactive","is_frozen":false},
        {"symbol":"MRNA","market_id":228,"market_type":"perp","status":"active","is_frozen":true}
    ]}"""

    private val tokenList = """{"tokens":[
        {"symbol":"BTC","name":"Bitcoin","gecko_id":"bitcoin","market":"PERPS","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"COIN","name":"Coinbase","gecko_id":"","market":"PERPS","asset_type":"RWA","categories":["STOCK"]},
        {"symbol":"ETH","name":"Ethereum","gecko_id":"ethereum","market":"PERPS","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"ETH/USDC","name":"Ethereum","gecko_id":"ethereum","market":"SPOT","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"META","name":"Meta","gecko_id":"","market":"PERPS","asset_type":"RWA","categories":["STOCK"]},
        {"symbol":"AAVE/USDC","name":"Aave","gecko_id":"aave","market":"SPOT","asset_type":"CRYPTO","categories":["DEFI"]},
        {"symbol":"EMPTY","name":" ","market":"PERPS","asset_type":"CRYPTO"}
    ]}"""

    private fun pairs(vararg bodies: String): Map<String?, MarketPair> {
        val out = ArrayList<MarketPair>()
        Lighter().readPairListCombined(bodies.toList(), out)
        return out.associateBy { it.pairId }
    }

    @Test
    fun combinedListMarksRwaAsTradFi() {
        val byId = pairs(orderBooks, tokenList)
        assertEquals(setOf("BTC", "COIN", "ETH/USDC", "NEWX"), byId.keys)
        val btc = byId.getValue("BTC")
        assertEquals("USDC", btc.quote)
        assertEquals(FuturesContractType.PERPETUAL, btc.contractType)
        assertFalse(btc.tradFi)
        assertTrue(byId.getValue("COIN").tradFi)
        val eth = byId.getValue("ETH/USDC")
        assertEquals("ETH", eth.base)
        assertEquals("USDC", eth.quote)
        assertEquals(FuturesContractType.NONE, eth.contractType)
        assertFalse(eth.tradFi)
        // ohne tokenlist-Eintrag (neu gelistet): unmarkiert
        assertFalse(byId.getValue("NEWX").tradFi)
    }

    @Test
    fun unreadableTokenListKeepsPairsUnmarked() {
        for (broken in listOf("", "<html>502</html>", """{"code":200}""")) {
            val byId = pairs(orderBooks, broken)
            assertEquals(setOf("BTC", "COIN", "ETH/USDC", "NEWX"), byId.keys)
            assertTrue(byId.values.none { it.tradFi })
        }
    }

    /** Robinhood-Aktientoken als Spot fehlen in `tokenlist` (echte Antwort vom 19.10.2026: «rhSPY/USDC», «rhQQQ/USDC»). */
    private val robinhoodBooks = """{"code":200,"order_books":[
        {"symbol":"rhSPY/USDC","market_id":2060,"market_type":"spot","status":"active","is_frozen":false},
        {"symbol":"rhQQQ/USDC","market_id":2061,"market_type":"spot","status":"active","is_frozen":false},
        {"symbol":"rhETH/USDC","market_id":2062,"market_type":"spot","status":"active","is_frozen":false},
        {"symbol":"NEWX","market_id":300,"market_type":"perp","status":"active","is_frozen":false}
    ]}"""

    private val robinhoodTokens = """{"tokens":[
        {"symbol":"QQQ","name":"Invesco QQQ","market":"PERPS","asset_type":"RWA","categories":["ETF"]},
        {"symbol":"ETH","name":"Ethereum","market":"PERPS","asset_type":"CRYPTO","categories":["MAJOR"]}
    ]}"""

    @Test
    fun robinhoodSpotWithoutTokenListEntryIsTradFi() {
        val byId = pairs(robinhoodBooks, robinhoodTokens)
        // SPY fehlt ganz, QQQ ist RWA: beide TradFi; Basis bleibt, wie Lighter sie schreibt
        assertTrue(byId.getValue("rhSPY/USDC").tradFi)
        assertEquals("rhSPY", byId.getValue("rhSPY/USDC").base)
        assertTrue(byId.getValue("rhQQQ/USDC").tradFi)
        // Rest als Krypto geführt: kein TradFi
        assertFalse(byId.getValue("rhETH/USDC").tradFi)
        assertFalse(byId.getValue("NEWX").tradFi)
        // ohne lesbare tokenlist reicht die Vorsilbe
        assertTrue(pairs(robinhoodBooks, "").getValue("rhSPY/USDC").tradFi)
        assertFalse(pairs(robinhoodBooks, "").getValue("NEWX").tradFi)
    }

    @Test
    fun namesPerBaseFromTokenList() {
        assertEquals(
            mapOf("BTC" to "Bitcoin", "COIN" to "Coinbase", "ETH" to "Ethereum", "META" to "Meta", "AAVE" to "Aave"),
            Lighter().parseBaseNamesCombined(listOf(orderBooks, tokenList)),
        )
    }

    @Test
    fun tradFiHelper() {
        assertTrue(TradFi.lighter("RWA"))
        assertTrue(TradFi.lighter("rwa"))
        assertFalse(TradFi.lighter("CRYPTO"))
        assertFalse(TradFi.lighter(""))
    }
}
