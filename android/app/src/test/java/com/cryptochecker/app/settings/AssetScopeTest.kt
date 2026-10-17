package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.MarketPair
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
    fun legacySwitch_mapsOnToBoth_offToTokens() {
        assertEquals(AssetScope.BOTH, AssetScope.fromLegacy(true))
        assertEquals(AssetScope.TOKENS, AssetScope.fromLegacy(false))
        assertEquals(AssetScope.TOKENS, AssetScope.fromLegacy(null))
    }

    @Test
    fun resolve_prefersNewValue_thenLegacy_thenDefault() {
        // Neuer Wert gesetzt: der alte Schalter zählt nicht mehr
        assertEquals(AssetScope.TRADFI, AssetScope.resolve("TRADFI", true))
        assertEquals(AssetScope.TOKENS, AssetScope.resolve("TOKENS", true))
        // Update von einer älteren Version: nur der alte Schalter
        assertEquals(AssetScope.BOTH, AssetScope.resolve(null, true))
        assertEquals(AssetScope.TOKENS, AssetScope.resolve(null, false))
        // Neu installiert
        assertEquals(AssetScope.TOKENS, AssetScope.resolve(null, null))
        // Unbekannter Wert (neuere Version nach Downgrade): alter Schalter, sonst Standard
        assertEquals(AssetScope.BOTH, AssetScope.resolve("STOCKS", true))
        assertEquals(AssetScope.TOKENS, AssetScope.resolve("STOCKS", null))
    }

    @Test
    fun backup_newKey_legacyKey_orKeepCurrent() {
        assertEquals(AssetScope.TRADFI, AssetScope.fromBackup("TRADFI", false))
        assertEquals(AssetScope.BOTH, AssetScope.fromBackup(" both ", null))
        // Ältere Sicherung: nur «includeTradFiFutures»
        assertEquals(AssetScope.BOTH, AssetScope.fromBackup(null, true))
        assertEquals(AssetScope.TOKENS, AssetScope.fromBackup("", false))
        // Weder noch: aktuelle Einstellung bleibt
        assertNull(AssetScope.fromBackup(null, null))
        assertNull(AssetScope.fromBackup("UNKNOWN", null))
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
    fun markets_tradFiOnlyKeepsTradFiExchanges() {
        assertTrue(AssetScope.TRADFI.allowsMarket("BinanceFutures"))
        assertFalse(AssetScope.TRADFI.allowsMarket("Binance"))
        assertFalse(AssetScope.TRADFI.allowsMarket("DexScreener"))
        assertTrue(AssetScope.TOKENS.allowsMarket("Binance"))
        assertTrue(AssetScope.BOTH.allowsMarket("DexScreener"))
    }

    @Test
    fun scoped_filtersPairs() {
        val btc = MarketPair("BTC", "USDT", null)
        val nvda = MarketPair("NVDA", "USDT", null, tradFi = true)
        val info = MarketPairsInfo(lastSyncDate = 5, pairs = listOf(btc, nvda))

        assertEquals(listOf(btc), info.scoped(AssetScope.TOKENS).pairs)
        assertEquals(listOf(nvda), info.scoped(AssetScope.TRADFI).pairs)
        assertSame(info, info.scoped(AssetScope.BOTH))
        assertEquals(5L, info.scoped(AssetScope.TRADFI).lastSyncDate)
    }

    @Test
    fun alpha_hiddenOnlyWhenSwitchedOff() {
        val aia = MarketPair("AIA", "USDT", null)
        val btc = MarketPair("BTC", "USDT", null)
        val alpha = setOf("AIA")
        assertTrue(AssetScope.TOKENS.allows(aia, "BinanceFutures"))
        assertEquals(emptySet<String>(), AssetScope.hiddenAlphaSymbols(includeAlpha = true, alphaSymbols = alpha))
        val hidden = AssetScope.hiddenAlphaSymbols(includeAlpha = false, alphaSymbols = alpha)
        assertFalse(AssetScope.TOKENS.allows(aia, "BinanceFutures", hidden))
        assertTrue(AssetScope.BOTH.allows(btc, "BinanceFutures", hidden))
        // An Binance Spot regulär gelistet: kein Alpha
        assertTrue(AssetScope.TOKENS.allows(aia, "Binance", hidden))
        val info = MarketPairsInfo(lastSyncDate = 1, pairs = listOf(btc, aia))
        assertEquals(listOf(btc), info.scoped(AssetScope.TOKENS, "BinanceFutures", hidden).pairs)
        assertSame(info, info.scoped(AssetScope.TOKENS, "BinanceFutures"))
    }

    /** Ab Build 43 für neue Installationen aus; bestehende behalten an ([InstallDefaultsTest]). */
    @Test
    fun alpha_defaultExcludedForNewInstalls() {
        assertFalse(AppSettings().includeAlphaTokens)
        assertTrue(AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS)
    }
}
