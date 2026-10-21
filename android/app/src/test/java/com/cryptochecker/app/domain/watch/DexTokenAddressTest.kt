package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.ui.features.watchlist.dexSpokenTail
import com.cryptochecker.marketdata.model.market.DexScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Token-Adresse der DEX-Pools fürs Paar-Blatt ([DexTokenAddress]). Wie `DexTokenAddressTests.swift`. */
class DexTokenAddressTest {

    private val evm = "0x6982508145454Ce325dDbE47a25d4ec3d2311933"

    @Test
    fun cleanedKeepsRealAddressesAndDropsJunk() {
        assertEquals(evm, DexTokenAddress.cleaned("  $evm "))
        assertEquals("So11111111111111111111111111111111111111112", DexTokenAddress.cleaned("So11111111111111111111111111111111111111112"))
        assertNull(DexTokenAddress.cleaned(null))
        assertNull(DexTokenAddress.cleaned("   "))
        assertNull(DexTokenAddress.cleaned("0x12 34"))
        assertNull(DexTokenAddress.cleaned("a".repeat(DexTokenAddress.MAX_LENGTH + 1)))
        assertEquals(DexTokenAddress.MAX_LENGTH, DexTokenAddress.cleaned("a".repeat(DexTokenAddress.MAX_LENGTH))!!.length)
    }

    @Test
    fun shownIsShortened() {
        assertEquals("0x6982…1933", DexTokenAddress.shown(evm))
        assertEquals("short", DexTokenAddress.shown("short"))
    }

    @Test
    fun searchResultCarriesTheAddressUsedForTheSheet() {
        val body = """{"pairs":[{"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xA43fe16908251ee70EF74718545e4FE6C5cCEc9f",""" +
            """"baseToken":{"address":"$evm","name":"Pepe","symbol":"PEPE"},"quoteToken":{"symbol":"WETH"},""" +
            """"priceUsd":"0.00001","liquidity":{"usd":1000000}}]}"""
        val pool = DexScreener.parseSearch(body, "pepe").single()
        assertEquals(evm, DexTokenAddress.cleaned(pool.baseAddress))
        // Schlüssel wie die Liquidität: Kennung ohne Gross-/Kleinschreibung
        assertEquals("ethereum/0xa43fe16908251ee70ef74718545e4fe6c5ccec9f", DexLiquidity.key(pool.pairId))
    }

    /** TalkBack: die letzten vier Zeichen der Adresse einzeln (Vorlesetext der Zeile im Aktionsblatt). Wie iOS. */
    @Test
    fun spokenTailSpellsLastFourCharacters() {
        assertEquals("1 9 3 3", dexSpokenTail(evm))
        assertEquals("a b c", dexSpokenTail(" abc "))
    }
}
