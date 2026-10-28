package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.marketdata.model.ExchangePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Token & TradFi» — wie `SettingsLogicTests.swift` (testAssetScope…). */
class AssetScopeTest {

    @Test
    fun default_isTokensOnly() {
        assertEquals(AssetScope.TOKENS, AssetScope.DEFAULT)
        assertEquals(AssetScope.TOKENS, AppSettings().assetScope)
    }

    @Test
    fun fromName_trimmedCaseInsensitive_unknownNull() {
        assertEquals(AssetScope.TRADFI, AssetScope.fromNameOrNull("TRADFI"))
        assertEquals(AssetScope.BOTH, AssetScope.fromNameOrNull(" both "))
        assertNull(AssetScope.fromNameOrNull(""))
        assertNull(AssetScope.fromNameOrNull(null))
        assertNull(AssetScope.fromNameOrNull("UNKNOWN"))
    }

    @Test
    fun helpers_tokensAndTradFi() {
        assertTrue(AssetScope.TOKENS.allowsTokens)
        assertFalse(AssetScope.TOKENS.allowsTradFi)
        assertFalse(AssetScope.TRADFI.allowsTokens)
        assertTrue(AssetScope.TRADFI.allowsTradFi)
        assertTrue(AssetScope.BOTH.allowsTokens)
        assertTrue(AssetScope.BOTH.allowsTradFi)
    }

    @Test
    fun exchanges_tradFiOnlyKeepsTradFiExchanges() {
        assertTrue(AssetScope.TRADFI.allowsExchange("BinanceFutures"))
        assertFalse(AssetScope.TRADFI.allowsExchange("Binance"))
        assertFalse(AssetScope.TRADFI.allowsExchange("DexScreener"))
        assertTrue(AssetScope.TOKENS.allowsExchange("Binance"))
        assertTrue(AssetScope.BOTH.allowsExchange("DexScreener"))
    }

    @Test
    fun scoped_filtersPairs() {
        val btc = ExchangePair("BTC", "USDT", null)
        val nvda = ExchangePair("NVDA", "USDT", null, tradFi = true)
        val info = ExchangePairsInfo(lastSyncDate = 5, pairs = listOf(btc, nvda))

        assertEquals(listOf(btc), info.scoped(AssetScope.TOKENS).pairs)
        assertEquals(listOf(nvda), info.scoped(AssetScope.TRADFI).pairs)
        assertSame(info, info.scoped(AssetScope.BOTH))
        assertEquals(5L, info.scoped(AssetScope.TRADFI).lastSyncDate)
    }

    @Test
    fun alpha_hiddenOnlyWhenSwitchedOff() {
        val aia = ExchangePair("AIA", "USDT", null)
        val btc = ExchangePair("BTC", "USDT", null)
        val alpha = setOf("AIA")
        assertTrue(AssetScope.TOKENS.allows(aia, "BinanceFutures"))
        assertEquals(emptySet<String>(), AssetScope.hiddenAlphaSymbols(includeAlpha = true, alphaSymbols = alpha))
        val hidden = AssetScope.hiddenAlphaSymbols(includeAlpha = false, alphaSymbols = alpha)
        assertFalse(AssetScope.TOKENS.allows(aia, "BinanceFutures", hidden))
        assertTrue(AssetScope.BOTH.allows(btc, "BinanceFutures", hidden))
        // An Binance Spot regulär gelistet: kein Alpha
        assertTrue(AssetScope.TOKENS.allows(aia, "Binance", hidden))
        val info = ExchangePairsInfo(lastSyncDate = 1, pairs = listOf(btc, aia))
        assertEquals(listOf(btc), info.scoped(AssetScope.TOKENS, "BinanceFutures", hidden).pairs)
        assertSame(info, info.scoped(AssetScope.TOKENS, "BinanceFutures"))
    }

    /** Ab Werk aus ([InstallDefaultsTest]). */
    @Test
    fun alpha_defaultExcludedForNewInstalls() {
        assertFalse(AppSettings().includeAlphaTokens)
    }
}
